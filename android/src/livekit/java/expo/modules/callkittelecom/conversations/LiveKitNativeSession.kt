package expo.modules.callkittelecom.conversations

import android.content.Context
import android.content.pm.ApplicationInfo
import io.livekit.android.*
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.mapNotNull
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Connected transports and decoded receive audio stay warm; Core alone authorizes audible output/capture. */
internal class LiveKitNativeSession(private val context: Context, invitationId: String) : NativeMediaSession {
    private val configuration = LiveKitNativeConfiguration()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var room: Room? = null
    override val isPrepared: Boolean get() = room != null
    private var microphone: LocalAudioTrack? = null
    private var publication: LocalTrackPublication? = null
    private var recording: JavaAudioDeviceModule? = null
    private var captureEnabled = false
    private var serverId = invitationId
    @Volatile private var active = false
    @Volatile private var held = false
    @Volatile private var muted = false
    @Volatile private var closed = false
    private var failed: () -> Unit = {}
    private var disconnected: (NativeMediaDisconnection) -> Unit = { failed() }
    private var connection: NativeRoomConnection? = null
    override fun setFailureListener(listener: () -> Unit) { failed = listener }
    override fun setDisconnectListener(listener: (NativeMediaDisconnection) -> Unit) { disconnected = listener }
    private var pcm = NativePcmProbe(invitationId)
    private var decoded = NativeDecodedAudioReadiness()
    private var warmth = NativeWarmAudio(invitationId)
    private val tracks = mutableSetOf<RemoteAudioTrack>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun prewarm(payload: Map<String, Any?>): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed && room == null)
        serverId = payload["serverCallId"] as? String ?: error("Invalid media invitation")
        val media = payload["media"] as? Map<String, Any?> ?: error("Missing media credentials")
        require(media["provider"] == "livekit")
        val url = media["url"] as? String ?: error("Missing media URL")
        requireNativeMediaUrl(url, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        require((media["expiresAt"] as? Number)?.toLong()?.let { it > System.currentTimeMillis() / 1000 } == true)
        val token = media["token"] as? String ?: error("Missing media token")
        pcm.close(); decoded.close(); warmth.close()
        val probe = NativePcmProbe(serverId).also { pcm = it }
        val decode = NativeDecodedAudioReadiness().also { decoded = it }
        val heat = NativeWarmAudio(serverId).also { warmth = it }
        val candidate = LiveKit.create(context, overrides = configuration.overrides(AudioOptions(
            audioHandler = NoAudioHandler(), disableAudioPrewarming = true, disableCommunicationModeWorkaround = true,
            javaAudioDeviceModuleCustomizer = { builder ->
                heat.configureOutput(builder)
                builder.setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                    override fun onWebRtcAudioTrackStart() { heat.playbackStarted() }
                    override fun onWebRtcAudioTrackStop() { heat.playbackStopped() }
                })
                builder.setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() { heat.pumpStarted() }
                    override fun onWebRtcAudioRecordStop() { heat.pumpStopped() }
                })
            })))
        val device = candidate.lkObjects.audioDeviceModule as? JavaAudioDeviceModule ?: error("Unsupported native audio module")
        // Set before any capture thread starts: recordingless RTP negotiation cannot open the microphone.
        device.setAudioRecordEnabled(false)
        recording = device
        candidate.setSpeakerMute(true); heat.muteOutput()
        val publisherReady = CompletableDeferred<Unit>()
        val subscriberReady = CompletableDeferred<Unit>()
        val remoteTrackReady = CompletableDeferred<Unit>()
        val transportJobs = mutableListOf<Job>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { candidate.events.collect { event ->
            when (event) {
                is RoomEvent.TrackPublished -> if (!closed) (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
                is RoomEvent.TrackSubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_SUBSCRIBED)
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_TRACK_AVAILABLE, mapOf("role" to 2L))
                    tracks += track; track.enabled = true
                    track.addSink(probe.decoderSink); track.addSink(probe.answerSink); track.addSink(decode)
                    remoteTrackReady.complete(Unit)
                }
                is RoomEvent.TrackUnsubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_UNSUBSCRIBED)
                    tracks.remove(track); runCatching { track.removeSink(probe.decoderSink); track.removeSink(probe.answerSink); track.removeSink(decode) }
                }
                else -> Unit
            }
        } }
        connection?.close()
        val observation = NativeRoomConnection(scope, { stage, reason -> NativeMediaSignals.emit(serverId, stage, reason = reason) }, { reason ->
            if (!closed && room === candidate) {
                room = null; transportJobs.forEach { it.cancel() }; collector.cancel()
                probe.close(); decode.close(); heat.close(); runCatching { candidate.release() }
                disconnected(reason?.disconnection() ?: NativeMediaDisconnection.FAILURE)
            }
        })
        connection = observation
        observation.observe(candidate.events.events.mapNotNull { event -> when (event) {
            is RoomEvent.Reconnecting -> NativeRoomState.RECONNECTING
            is RoomEvent.Reconnected -> NativeRoomState.RECONNECTED
            is RoomEvent.Disconnected -> NativeRoomState.DISCONNECTED(event.reason)
            else -> null
        } })
        NativeMediaSignals.emit(serverId, NativeMediaStage.MEDIA_CONNECT_STARTED)
        NativeMediaSignals.emit(serverId, NativeMediaStage.SUBSCRIPTION_REQUESTED)
        NativeTransportReadiness(CoroutineScope(scope.coroutineContext + Dispatchers.IO)).apply {
            transportJobs += watch(candidate::getPublisherRTCStats, publisherReady, NativeTransportProbe(serverId, 1L)::request)
            transportJobs += watch(candidate::getSubscriberRTCStats, subscriberReady, NativeTransportProbe(serverId, 2L)::request)
        }
        try {
            // Own the candidate during connect too: a server end can arrive before connect returns.
            room = candidate
            candidate.connect(url, token, ConnectOptions(autoSubscribe = true, audio = false, video = false))
            check(!closed)
            require(candidate.name == media["room"] && candidate.localParticipant.identity?.value == media["identity"])
            NativeMediaSignals.emit(serverId, NativeMediaStage.MEDIA_CONNECT_COMPLETED)
            NativeMediaSignals.emit(serverId, NativeMediaStage.ROOM_CONNECTED)
            publishMutedMicrophone()
            // The prepublished sender must be negotiated before answer. Remote media is
            // optional: a silent/muted caller, or one not yet publishing, remains answerable.
            publisherReady.await()
            try {
                withTimeoutOrNull(2000) {
                    remoteTrackReady.await(); subscriberReady.await()
                    heat.awaitPlaybackStarted(); heat.awaitMutedOutput(); decode.awaitDecoded()
                }
            } catch (_: CancellationException) {
                // Silent, unpublished or retired remote tracks must remain answerable.
                currentCoroutineContext().ensureActive()
            }
            check(!closed && room === candidate)
            NativeMediaSignals.emit(serverId, NativeMediaStage.PREJOINED)
        } catch (error: Throwable) {
            // LiveKit waits for disconnect emission; drain it before recording our release.
            observation.closeForRelease()
            collector.cancel(); transportJobs.forEach { it.cancel() }
            probe.close(); decode.close(); heat.close(); room = null
            tracks.forEach { runCatching { it.removeSink(probe.decoderSink); it.removeSink(probe.answerSink); it.removeSink(decode) } }; tracks.clear()
            microphone = null; publication = null; captureEnabled = false; recording = null
            runCatching { candidate.release() }
            throw error
        }
    }

    private suspend fun publishMutedMicrophone() {
        val participant = room?.localParticipant ?: error("Media not prepared")
        recording?.setAudioRecordEnabled(false)
        val track = participant.createAudioTrack().also { microphone = it }
        // Install through the SDK dispatcher before starting the silent capture pump.
        track.setAudioBufferCallback(warmth)
        NativeMediaSignals.emit(serverId, NativeMediaStage.MICROPHONE_PUBLISH_STARTED)
        track.start(); track.enabled = false
        try {
            check(participant.publishAudioTrack(track))
            publication = participant.trackPublications.values.firstOrNull { it.track === track } as? LocalTrackPublication
                ?: error("Missing microphone publication")
            publication!!.muted = true
            NativeMediaSignals.emit(serverId, NativeMediaStage.MICROPHONE_PUBLISHED)
        } catch (error: Throwable) {
            participant.unpublishTrack(track); track.stop(); track.stopPrewarm()
            runCatching { track.dispose() }; throw error
        }
    }

    override suspend fun activate(): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed && room != null && publication != null)
        active = true; held = false
        releaseOutput()
        setMicrophone(!muted)
    }
    private fun releaseOutput() {
        warmth.releaseOutput()
        room?.setSpeakerMute(false)
        pcm.arm()
    }
    override suspend fun setHeld(held: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed); this@LiveKitNativeSession.held = held
        if (!active) return@withContext
        if (held) { room?.setSpeakerMute(true); warmth.muteOutput(); pcm.disarm() } else releaseOutput()
        setMicrophone(!held && !muted)
    }
    override suspend fun setMuted(muted: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed); this@LiveKitNativeSession.muted = muted
        if (active) setMicrophone(!held && !muted)
    }
    override suspend fun playTones(digits: String): Unit = withContext(Dispatchers.Main.immediate) {
        val tones = NativeDtmfDigit.parse(digits)
        for (tone in tones) {
            ensureActive()
            check(!closed && active && !held) { "invalid_arguments" }
            val participant = room?.localParticipant ?: error("invalid_arguments")
            participant.publishDtmf(tone.code, tone.digit).getOrThrow()
        }
    }
    private suspend fun setMicrophone(enabled: Boolean) {
        if (enabled) {
            if (captureEnabled) return
            if (publication == null) publishMutedMicrophone()
            val ready = warmth.armCapture()
            captureEnabled = true
            recording?.setAudioRecordEnabled(true)
            warmth.publishRecordingEnabled()
            publication!!.muted = false
            withTimeout(1500) { ready.await() }
        } else if (captureEnabled) {
            // Runtime recordingless toggles are asynchronous; detach the sender to stop physical capture.
            publication?.muted = true
            val retired = microphone
            retired?.let { room?.localParticipant?.unpublishTrack(it); it.stopPrewarm() }
            publication = null; microphone = null; captureEnabled = false
            try { warmth.awaitPumpStopped() } finally { runCatching { retired?.dispose() } }
            recording?.setAudioRecordEnabled(false)
        } else {
            // An initially muted answer retains the already-negotiated silent publication.
            publication?.muted = true
        }
    }
    override suspend fun close(reason: String): Unit = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext
        closed = true; active = false; pcm.close(); decoded.close(); warmth.close()
        // release() initiates a local disconnect after we retire room observation.
        if (room != null) connection?.closeForRelease() else connection?.close()
        connection = null
        scope.cancel(); tracks.forEach { runCatching { it.removeSink(pcm.decoderSink); it.removeSink(pcm.answerSink); it.removeSink(decoded) } }; tracks.clear()
        microphone?.let { track ->
            runCatching { room?.localParticipant?.unpublishTrack(track) }
            runCatching { track.stopPrewarm(); track.dispose() }
        }
        microphone = null; publication = null; captureEnabled = false; recording = null
        runCatching { room?.release() }; room = null
        NativeMediaSignals.emit(serverId, NativeMediaStage.CLOSED)
    }
}
