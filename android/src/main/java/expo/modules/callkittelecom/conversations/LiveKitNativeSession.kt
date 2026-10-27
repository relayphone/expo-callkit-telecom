package expo.modules.callkittelecom.conversations

import android.content.Context
import io.livekit.android.*
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import kotlinx.coroutines.*
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Telecom alone authorizes audio. A pre-join opens no capture, playback, focus or audio mode. */
internal class LiveKitNativeSession(private val context: Context, invitationId: String) : NativeMediaSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var room: Room? = null
    override val isPrepared: Boolean get() = room != null
    private var microphone: LocalAudioTrack? = null
    private var microphonePublished = false
    private var serverId = invitationId
    @Volatile private var active = false
    @Volatile private var held = false
    @Volatile private var muted = false
    @Volatile private var closed = false
    private var failed: () -> Unit = {}
    override fun setFailureListener(listener: () -> Unit) { failed = listener }
    private val pcm = NativePcmProbe(invitationId)
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
        val candidate = LiveKit.create(context, overrides = LiveKitOverrides(audioOptions = AudioOptions(
            audioHandler = NoAudioHandler(), disableAudioPrewarming = true, disableCommunicationModeWorkaround = true,
            javaAudioDeviceModuleCustomizer = { builder ->
                builder.setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                    override fun onWebRtcAudioTrackStart() { NativeMediaSignals.emit(serverId, NativeMediaStage.PLAYBACK_STARTED) }
                    override fun onWebRtcAudioTrackStop() { NativeMediaSignals.emit(serverId, NativeMediaStage.PLAYBACK_STOPPED) }
                })
                builder.setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() { NativeMediaSignals.emit(serverId, NativeMediaStage.CAPTURE_STARTED) }
                    override fun onWebRtcAudioRecordStop() { NativeMediaSignals.emit(serverId, NativeMediaStage.CAPTURE_STOPPED) }
                })
            })))
        val transportJobs = mutableListOf<Job>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { candidate.events.collect { event ->
            when (event) {
                is RoomEvent.TrackPublished -> if (active && !held) {
                    NativeMediaSignals.emit(serverId, NativeMediaStage.SUBSCRIPTION_REQUESTED)
                    (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
                }
                is RoomEvent.TrackSubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_SUBSCRIBED)
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_TRACK_AVAILABLE, mapOf("role" to 2L))
                    tracks += track; track.enabled = active && !held
                    track.addSink(pcm.decoderSink); track.addSink(pcm.answerSink)
                }
                is RoomEvent.TrackUnsubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_UNSUBSCRIBED)
                    tracks.remove(track); runCatching { track.removeSink(pcm.decoderSink); track.removeSink(pcm.answerSink) }
                }
                is RoomEvent.Disconnected -> if (!closed && room === candidate) {
                    room = null; transportJobs.forEach { it.cancel() }
                    runCatching { candidate.release() }
                    if (active) failed()
                    currentCoroutineContext().cancel()
                }
                else -> Unit
            }
        } }
        NativeMediaSignals.emit(serverId, NativeMediaStage.MEDIA_CONNECT_STARTED)
        NativeTransportProbe(serverId, CoroutineScope(scope.coroutineContext + Dispatchers.IO)).apply {
            transportJobs += watch(1L, candidate::getPublisherRTCStats)
            transportJobs += watch(2L, candidate::getSubscriberRTCStats)
        }
        try {
            candidate.connect(url, token, ConnectOptions(autoSubscribe = false, audio = false, video = false))
            check(!closed)
            require(candidate.name == media["room"] && candidate.localParticipant.identity?.value == media["identity"])
            room = candidate
            NativeMediaSignals.emit(serverId, NativeMediaStage.MEDIA_CONNECT_COMPLETED)
            NativeMediaSignals.emit(serverId, NativeMediaStage.ROOM_CONNECTED)
            NativeMediaSignals.emit(serverId, NativeMediaStage.PREJOINED)
        } catch (error: Throwable) {
            collector.cancel(); transportJobs.forEach { it.cancel() }; room = null
            runCatching { candidate.release() }
            throw error
        }
    }
    override suspend fun activate(): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        check(room != null) { "Media not prepared" }
        active = true; held = false; pcm.arm()
        subscriptions(true)
        setMicrophone(!muted)
        Unit
    }
    override suspend fun setHeld(held: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        this@LiveKitNativeSession.held = held
        if (!active) return@withContext
        if (held) pcm.disarm() else pcm.arm()
        tracks.forEach { it.enabled = !held }
        subscriptions(!held)
        setMicrophone(!held && !muted)
    }
    override suspend fun setMuted(muted: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        this@LiveKitNativeSession.muted = muted
        if (active) setMicrophone(!held && !muted)
    }
    private suspend fun setMicrophone(enabled: Boolean) {
        val participant = room?.localParticipant ?: error("Media not prepared")
        if (enabled && !microphonePublished) {
            val track = microphone ?: participant.createAudioTrack().also { microphone = it }
            NativeMediaSignals.emit(serverId, NativeMediaStage.MICROPHONE_PUBLISH_STARTED)
            track.start()
            try {
                check(participant.publishAudioTrack(track)); microphonePublished = true
                NativeMediaSignals.emit(serverId, NativeMediaStage.MICROPHONE_PUBLISHED)
            }
            catch (error: Throwable) { participant.unpublishTrack(track); track.stop(); track.stopPrewarm(); throw error }
        } else if (!enabled && microphonePublished) {
            // Unpublish detaches the RTP sender; muting alone leaves AudioRecord running.
            microphone?.let { participant.unpublishTrack(it); it.stopPrewarm() }
            microphonePublished = false
        }
    }
    private fun subscriptions(enabled: Boolean) {
        if (enabled) NativeMediaSignals.emit(serverId, NativeMediaStage.SUBSCRIPTION_REQUESTED)
        room?.remoteParticipants?.values?.forEach { participant ->
            participant.trackPublications.values.forEach { (it as? RemoteTrackPublication)?.setSubscribed(enabled) }
        }
    }
    override suspend fun close(reason: String): Unit = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext
        closed = true; active = false; pcm.close()
        scope.cancel(); tracks.forEach { runCatching { it.removeSink(pcm.decoderSink); it.removeSink(pcm.answerSink) } }; tracks.clear()
        microphone?.let { track ->
            runCatching { room?.localParticipant?.unpublishTrack(track) }
            runCatching { track.stopPrewarm(); track.dispose() }
        }
        microphone = null; microphonePublished = false
        runCatching { room?.release() }; room = null
        NativeMediaSignals.emit(serverId, NativeMediaStage.CLOSED)
    }
}
