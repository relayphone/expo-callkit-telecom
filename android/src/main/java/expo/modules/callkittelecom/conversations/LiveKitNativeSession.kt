package expo.modules.callkittelecom.conversations

import android.content.Context
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
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Connected transports and decoded receive audio stay warm; Core alone authorizes audible output/capture. */
internal class LiveKitNativeSession(private val context: Context, invitationId: String) : NativeMediaSession {
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
    override fun setFailureListener(listener: () -> Unit) { failed = listener }
    private var pcm = NativePcmProbe(invitationId)
    private var warmth = NativeWarmAudio(invitationId)
    private val tracks = mutableSetOf<RemoteAudioTrack>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun prewarm(payload: Map<String, Any?>): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed && room == null)
        serverId = payload["serverCallId"] as? String ?: error("Invalid media invitation")
        val media = payload["media"] as? Map<String, Any?> ?: error("Missing media credentials")
        require(media["provider"] == "livekit")
        val url = media["url"] as? String ?: error("Missing media URL")
        require(java.net.URI(url).let { it.scheme == "wss" && it.host != null && it.userInfo == null })
        require((media["expiresAt"] as? Number)?.toLong()?.let { it > System.currentTimeMillis() / 1000 } == true)
        val token = media["token"] as? String ?: error("Missing media token")
        pcm.close(); warmth.close()
        val decode = NativePcmProbe(serverId).also { pcm = it }
        val heat = NativeWarmAudio(serverId).also { warmth = it }
        val candidate = LiveKit.create(context, overrides = LiveKitOverrides(audioOptions = AudioOptions(
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
        val transportJobs = mutableListOf<Job>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { candidate.events.collect { event ->
            when (event) {
                is RoomEvent.TrackPublished -> if (!closed) (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
                is RoomEvent.TrackSubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_SUBSCRIBED)
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_TRACK_AVAILABLE, mapOf("role" to 2L))
                    tracks += track; track.enabled = true
                    track.addSink(decode.decoderSink); track.addSink(decode.answerSink)
                }
                is RoomEvent.TrackUnsubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_UNSUBSCRIBED)
                    tracks.remove(track); runCatching { track.removeSink(decode.decoderSink); track.removeSink(decode.answerSink) }
                }
                is RoomEvent.Disconnected -> if (!closed && room === candidate) {
                    room = null; transportJobs.forEach { it.cancel() }
                    decode.close(); heat.close(); runCatching { candidate.release() }
                    if (active) failed()
                    currentCoroutineContext().cancel()
                }
                else -> Unit
            }
        } }
        NativeMediaSignals.emit(serverId, NativeMediaStage.MEDIA_CONNECT_STARTED)
        NativeMediaSignals.emit(serverId, NativeMediaStage.SUBSCRIPTION_REQUESTED)
        NativeTransportProbe(serverId, CoroutineScope(scope.coroutineContext + Dispatchers.IO)).apply {
            transportJobs += watch(1L, candidate::getPublisherRTCStats, publisherReady)
            transportJobs += watch(2L, candidate::getSubscriberRTCStats, subscriberReady)
        }
        try {
            candidate.connect(url, token, ConnectOptions(autoSubscribe = true, audio = false, video = false))
            check(!closed)
            require(candidate.name == media["room"] && candidate.localParticipant.identity?.value == media["identity"])
            room = candidate
            NativeMediaSignals.emit(serverId, NativeMediaStage.MEDIA_CONNECT_COMPLETED)
            NativeMediaSignals.emit(serverId, NativeMediaStage.ROOM_CONNECTED)
            publishMutedMicrophone()
            publisherReady.await(); subscriberReady.await()
            decode.awaitDecoded(); heat.awaitPlaybackStarted(); heat.awaitMutedOutput()
            NativeMediaSignals.emit(serverId, NativeMediaStage.PREJOINED)
        } catch (error: Throwable) {
            collector.cancel(); transportJobs.forEach { it.cancel() }
            decode.close(); heat.close(); room = null
            tracks.forEach { runCatching { it.removeSink(decode.decoderSink); it.removeSink(decode.answerSink) } }; tracks.clear()
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
        room?.setSpeakerMute(false)
        warmth.releaseOutput(); pcm.arm()
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
    private suspend fun setMicrophone(enabled: Boolean) {
        if (enabled) {
            if (captureEnabled) return
            if (publication == null) publishMutedMicrophone()
            val ready = warmth.armCapture()
            warmth.flushSilentWindow()
            captureEnabled = true
            recording?.setAudioRecordEnabled(true)
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
        closed = true; active = false; pcm.close(); warmth.close()
        scope.cancel(); tracks.forEach { runCatching { it.removeSink(pcm.decoderSink); it.removeSink(pcm.answerSink) } }; tracks.clear()
        microphone?.let { track ->
            runCatching { room?.localParticipant?.unpublishTrack(track) }
            runCatching { track.stopPrewarm(); track.dispose() }
        }
        microphone = null; publication = null; captureEnabled = false; recording = null
        runCatching { room?.release() }; room = null
        NativeMediaSignals.emit(serverId, NativeMediaStage.CLOSED)
    }
}
