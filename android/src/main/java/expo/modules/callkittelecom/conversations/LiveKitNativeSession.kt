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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.*
import livekit.org.webrtc.AudioTrackSink
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
    private val firstAudio = AtomicBoolean(false)
    private val tracks = mutableSetOf<RemoteAudioTrack>()
    private val sink = object : AudioTrackSink {
        override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, capturedAt: Long) {
            if (!active || held || closed || bits != 16 || firstAudio.get()) return
            val pcm = data.duplicate().order(ByteOrder.nativeOrder())
            var peak = 0
            while (pcm.remaining() >= 2) peak = maxOf(peak, abs(pcm.short.toInt()))
            // Explicit PCM threshold; the timing result records it rather than measuring physical sound.
            if (peak >= 128 && firstAudio.compareAndSet(false, true))
                NativeMediaSignals.emit(serverId, NativeMediaStage.FIRST_AUDIO, mapOf("frames" to frames.toLong(),
                    "sampleRate" to rate.toLong(), "channels" to channels.toLong(), "peak" to peak.toLong(), "threshold" to 128L))
        }
    }

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
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { candidate.events.collect { event ->
            when (event) {
                is RoomEvent.TrackPublished -> if (active && !held) (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
                is RoomEvent.TrackSubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_SUBSCRIBED)
                    tracks += track; track.enabled = active && !held; track.addSink(sink)
                }
                is RoomEvent.TrackUnsubscribed -> (event.track as? RemoteAudioTrack)?.let { track ->
                    NativeMediaSignals.emit(serverId, NativeMediaStage.REMOTE_UNSUBSCRIBED)
                    tracks.remove(track); runCatching { track.removeSink(sink) }
                }
                is RoomEvent.Disconnected -> if (!closed && room === candidate) {
                    room = null
                    runCatching { candidate.release() }
                    if (active) failed()
                    currentCoroutineContext().cancel()
                }
                else -> Unit
            }
        } }
        try {
            candidate.connect(url, token, ConnectOptions(autoSubscribe = false, audio = false, video = false))
            check(!closed)
            require(candidate.name == media["room"] && candidate.localParticipant.identity?.value == media["identity"])
            room = candidate
            NativeMediaSignals.emit(serverId, NativeMediaStage.ROOM_CONNECTED)
            NativeMediaSignals.emit(serverId, NativeMediaStage.PREJOINED)
        } catch (error: Throwable) {
            collector.cancel(); room = null
            runCatching { candidate.release() }
            throw error
        }
    }
    override suspend fun activate(): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        check(room != null) { "Media not prepared" }
        active = true; held = false; firstAudio.set(false)
        subscriptions(true)
        setMicrophone(!muted)
        Unit
    }
    override suspend fun setHeld(held: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed)
        this@LiveKitNativeSession.held = held
        if (!active) return@withContext
        if (!held) firstAudio.set(false)
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
            track.start()
            try { check(participant.publishAudioTrack(track)); microphonePublished = true }
            catch (error: Throwable) { participant.unpublishTrack(track); track.stop(); track.stopPrewarm(); throw error }
        } else if (!enabled && microphonePublished) {
            // Unpublish detaches the RTP sender; muting alone leaves AudioRecord running.
            microphone?.let { participant.unpublishTrack(it); it.stopPrewarm() }
            microphonePublished = false
        }
    }
    private fun subscriptions(enabled: Boolean) {
        room?.remoteParticipants?.values?.forEach { participant ->
            participant.trackPublications.values.forEach { (it as? RemoteTrackPublication)?.setSubscribed(enabled) }
        }
    }
    override suspend fun close(reason: String): Unit = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext
        closed = true; active = false
        scope.cancel(); tracks.forEach { runCatching { it.removeSink(sink) } }; tracks.clear()
        microphone?.let { track ->
            runCatching { room?.localParticipant?.unpublishTrack(track) }
            runCatching { track.stopPrewarm(); track.dispose() }
        }
        microphone = null; microphonePublished = false
        runCatching { room?.release() }; room = null
        NativeMediaSignals.emit(serverId, NativeMediaStage.CLOSED)
    }
}
