package expo.modules.callkittelecom.conversations

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.dagger.OverridesModule
import io.livekit.android.room.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.*
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Raw WebRTC over an explicit authenticated HTTPS SDP exchange, without a JS media gate. */
internal class WebRTCNativeSession(private val context: Context, invitationId: String) : NativeMediaSession {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ConversationWebRTC") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var owner: Room? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var microphone: AudioTrack? = null
    private val remote = mutableSetOf<AudioTrack>()
    private val subscribed = mutableSetOf<AudioTrack>()
    @Volatile private var prepared = false
    @Volatile private var active = false
    @Volatile private var held = false
    @Volatile private var closed = false
    private var muted = false
    private var serverId = invitationId
    private var eventId = ""
    private var url = ""
    private var token = ""
    private var negotiationId = ""
    private var exchangeAttempted = false
    private var failed: () -> Unit = {}
    override val isPrepared get() = prepared
    override fun setFailureListener(listener: () -> Unit) { failed = listener }
    private val firstAudio = AtomicBoolean(false)
    private val sink = object : AudioTrackSink {
        override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, capturedAt: Long) {
            if (!active || held || closed || bits != 16 || firstAudio.get()) return
            val pcm = data.duplicate().order(ByteOrder.nativeOrder())
            var peak = 0
            while (pcm.remaining() >= 2) peak = maxOf(peak, abs(pcm.short.toInt()))
            if (peak >= 128 && firstAudio.compareAndSet(false, true))
                NativeMediaSignals.emit(serverId, NativeMediaStage.FIRST_AUDIO, mapOf("frames" to frames.toLong(),
                    "sampleRate" to rate.toLong(), "channels" to channels.toLong(), "peak" to peak.toLong(), "threshold" to 128L))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun prewarm(payload: Map<String, Any?>): Unit = withContext(worker) {
        check(!closed && peer == null)
        if (exchangeAttempted) withTimeoutOrNull(500) { runCatching { signal("ended") } }
        exchangeAttempted = false
        negotiationId = java.util.UUID.randomUUID().toString()
        serverId = payload["serverCallId"] as? String ?: error("Invalid WebRTC invitation")
        eventId = payload["eventId"] as? String ?: error("Invalid WebRTC invitation")
        val media = payload["media"] as? Map<String, Any?> ?: error("Missing WebRTC credentials")
        require(media["provider"] == "webrtc")
        require((media["expiresAt"] as? Number)?.toLong()?.let { it > System.currentTimeMillis()/1000 } == true)
        url = media["url"] as? String ?: error("Missing WebRTC endpoint")
        token = media["token"] as? String ?: error("Missing WebRTC token")
        require(token.isNotBlank())
        val ice = (media["iceServers"] as? List<Map<String, Any?>> ?: emptyList()).map { server ->
            val urls = server["urls"] as? List<String> ?: error("Invalid ICE server")
            require(urls.isNotEmpty() && urls.all { it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:") })
            PeerConnection.IceServer.builder(urls).setUsername(server["username"] as? String ?: "")
                .setPassword(server["credential"] as? String ?: "").createIceServer()
        }
        val connected = CompletableDeferred<Unit>()
        // The pinned SDK owns global libwebrtc initialization and factory disposal. Reuse its
        // runtime rather than reinitializing libwebrtc while another call's factory is alive.
        val component = DaggerLiveKitComponent.factory().create(context.applicationContext,
            OverridesModule(LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler(),
                disableAudioPrewarming = true, disableCommunicationModeWorkaround = true,
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder.setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                        override fun onWebRtcAudioRecordStart() { emit(NativeMediaStage.CAPTURE_STARTED) }
                        override fun onWebRtcAudioRecordStop() { emit(NativeMediaStage.CAPTURE_STOPPED) }
                    })
                    builder.setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                        override fun onWebRtcAudioTrackStart() { emit(NativeMediaStage.PLAYBACK_STARTED) }
                        override fun onWebRtcAudioTrackStop() { emit(NativeMediaStage.PLAYBACK_STOPPED) }
                    })
                }))))
        owner = component.roomFactory().create(context.applicationContext)
        try {
            val factory = component.peerConnectionFactory()
            lateinit var candidate: PeerConnection
            val observer = object : PeerConnection.Observer {
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    scope.launch {
                        if (peer !== candidate || closed) return@launch
                        when (state) {
                            PeerConnection.PeerConnectionState.CONNECTED -> connected.complete(Unit)
                            PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> {
                                connected.completeExceptionally(Exception("WebRTC connection failed"))
                                val wasActive = active
                                dispose()
                                if (wasActive) failed()
                            }
                            else -> Unit
                        }
                    }
                }
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
            }
            candidate = factory.createPeerConnection(PeerConnection.RTCConfiguration(ice).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }, observer) ?: error("WebRTC unavailable")
            peer = candidate
            candidate.setAudioPlayout(false); candidate.setAudioRecording(false)
            source = factory.createAudioSource(MediaConstraints())
            microphone = factory.createAudioTrack("native-microphone", source).also { it.setEnabled(false); candidate.addTrack(it) }
            val offer = checkNotNull(sdp { candidate.createOffer(it, MediaConstraints()) })
            sdp { candidate.setLocalDescription(it, offer) }
            withTimeout(4000) { while (candidate.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) delay(10) }
            exchangeAttempted = true
            val answer = NativeHttpCredentials().post(url, token, mapOf("type" to "offer", "sdp" to candidate.localDescription.description,
                "eventId" to eventId, "serverCallId" to serverId, "negotiationId" to negotiationId))
            require(answer["type"] == "answer")
            val description = answer["sdp"] as? String ?: error("Missing SDP answer")
            sdp { candidate.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, description)) }
            // These receiver wrappers live until peer disposal. The onTrack callback's
            // transceiver wrapper is transient and must not escape onto another executor.
            candidate.receivers.forEach { receiver -> (receiver.track() as? AudioTrack)?.let { track ->
                remote += track; track.setEnabled(false)
            } }
            check(remote.isNotEmpty()) { "SDP answer has no audio" }
            connected.await()
            check(!closed && peer === candidate)
            prepared = true
            emit(NativeMediaStage.PREJOINED)
            signal("prejoined")
        } catch (error: Throwable) { dispose(); throw error }
    }

    private suspend fun sdp(start: (SdpObserver) -> Unit): SessionDescription? = suspendCancellableCoroutine { result ->
        start(object : SdpObserver {
            override fun onCreateSuccess(value: SessionDescription) { if (result.isActive) result.resume(value) }
            override fun onSetSuccess() { if (result.isActive) result.resume(null) }
            override fun onCreateFailure(message: String) { if (result.isActive) result.resumeWithException(Exception("SDP creation failed")) }
            override fun onSetFailure(message: String) { if (result.isActive) result.resumeWithException(Exception("SDP application failed")) }
        })
    }
    override suspend fun activate(): Unit = withContext(worker) {
        check(!closed && prepared)
        active = true; held = false; firstAudio.set(false)
        applyAudio()
        scope.launch { runCatching { signal("answered") } }; Unit
    }
    override suspend fun setHeld(held: Boolean): Unit = withContext(worker) {
        check(!closed)
        this@WebRTCNativeSession.held = held
        if (active) { if (!held) firstAudio.set(false); applyAudio() }
    }
    override suspend fun setMuted(muted: Boolean): Unit = withContext(worker) {
        check(!closed)
        this@WebRTCNativeSession.muted = muted
        if (active) applyAudio()
    }
    private fun applyAudio() {
        val enabled = active && !held
        microphone?.setEnabled(enabled && !muted)
        peer?.setAudioRecording(enabled && !muted)
        peer?.setAudioPlayout(enabled)
        for (track in remote) {
            track.setEnabled(enabled)
            if (enabled && subscribed.add(track)) { track.addSink(sink); emit(NativeMediaStage.REMOTE_SUBSCRIBED) }
            else if (!enabled && subscribed.remove(track)) { track.removeSink(sink); emit(NativeMediaStage.REMOTE_UNSUBSCRIBED) }
        }
    }
    private fun emit(stage: NativeMediaStage) = NativeMediaSignals.emit(serverId, stage)
    private suspend fun signal(state: String) {
        NativeHttpCredentials().post(url, token, mapOf("type" to "state", "state" to state, "eventId" to eventId,
            "serverCallId" to serverId, "negotiationId" to negotiationId))
    }
    private fun dispose() {
        prepared = false; active = false
        remote.forEach { runCatching { it.removeSink(sink) } }; remote.clear(); subscribed.clear()
        runCatching { peer?.close(); peer?.dispose() }; peer = null
        runCatching { microphone?.dispose() }; microphone = null
        runCatching { source?.dispose() }; source = null
        runCatching { owner?.release() }; owner = null
    }
    override suspend fun close(reason: String) {
        if (closed) return
        withContext(worker) {
            if (closed) return@withContext
            closed = true
            scope.coroutineContext.cancelChildren()
            try { if (exchangeAttempted) withTimeoutOrNull(500) { runCatching { signal(reason) } } }
            finally { dispose(); emit(NativeMediaStage.CLOSED) }
        }
        scope.cancel(); worker.close()
    }
}
