package expo.modules.callkittelecom.conversations

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.dagger.OverridesModule
import io.livekit.android.room.Room
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Raw WebRTC over an explicit authenticated HTTPS SDP exchange, without a JS media gate. */
internal class WebRTCNativeSession(private val context: Context, invitationId: String) : NativeMediaSession {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ConversationWebRTC") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var owner: Room? = null
    private var transportProbe: Job? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var microphone: AudioTrack? = null
    private var recording: JavaAudioDeviceModule? = null
    private var captureEnabled = false
    private var pumpEnabled = false
    private val remote = mutableSetOf<AudioTrack>()
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
    private var pcm = NativePcmProbe(invitationId)
    private var warmth = NativeWarmAudio(invitationId)

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
        pcm.close(); warmth.close()
        val attemptServer = serverId
        val decode = NativePcmProbe(attemptServer).also { pcm = it }
        val heat = NativeWarmAudio(attemptServer).also { warmth = it }
        emit(NativeMediaStage.MEDIA_CONNECT_STARTED)
        val connected = CompletableDeferred<Unit>()
        val transportReady = CompletableDeferred<Unit>()
        // The pinned SDK owns global libwebrtc initialization and factory disposal. Reuse its
        // runtime rather than reinitializing libwebrtc while another call's factory is alive.
        val component = DaggerLiveKitComponent.factory().create(context.applicationContext,
            OverridesModule(LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler(),
                disableAudioPrewarming = true, disableCommunicationModeWorkaround = true,
                javaAudioDeviceModuleCustomizer = { builder ->
                    builder.setAudioBufferCallback(heat)
                    heat.configureOutput(builder)
                    builder.setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                        override fun onWebRtcAudioRecordStart() { heat.pumpStarted() }
                        override fun onWebRtcAudioRecordStop() { heat.pumpStopped() }
                    })
                    builder.setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                        override fun onWebRtcAudioTrackStart() { heat.playbackStarted() }
                        override fun onWebRtcAudioTrackStop() { heat.playbackStopped() }
                    })
                }))))
        owner = component.roomFactory().create(context.applicationContext)
        try {
            val device = owner!!.lkObjects.audioDeviceModule as? JavaAudioDeviceModule
                ?: error("Unsupported native audio module")
            // Set before any peer can start its audio threads. The warm pump must not read the microphone.
            device.setAudioRecordEnabled(false)
            device.setSpeakerMute(true); heat.muteOutput()
            recording = device
            val factory = component.peerConnectionFactory()
            lateinit var candidate: PeerConnection
            val observer = object : PeerConnection.Observer {
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    val observedAt = android.os.SystemClock.elapsedRealtimeNanos()
                    scope.launch {
                        if (peer !== candidate || closed) return@launch
                        when (state) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                NativeMediaSignals.emit(attemptServer, NativeMediaStage.TRANSPORT_CONNECTED,
                                    mapOf("role" to 0L, "sampled" to 0L), observedAt)
                                connected.complete(Unit)
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> {
                                val error = Exception("WebRTC connection failed")
                                connected.completeExceptionally(error)
                                transportReady.completeExceptionally(error)
                                val wasActive = active
                                dispose()
                                if (wasActive) failed()
                            }
                            else -> Unit
                        }
                    }
                }
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    val observedAt = android.os.SystemClock.elapsedRealtimeNanos()
                    val stage = when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> NativeMediaStage.ICE_CHECKING
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> NativeMediaStage.ICE_CONNECTED
                        else -> null
                    }
                    scope.launch {
                        if (peer !== candidate || closed) return@launch
                        stage?.let { NativeMediaSignals.emit(attemptServer, it, mapOf("role" to 0L, "sampled" to 0L), observedAt) }
                    }
                }
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
            transportProbe = NativeTransportProbe(attemptServer, scope).watch(0L, candidate::getStats, transportReady)
            candidate.setAudioPlayout(true); candidate.setAudioRecording(true); pumpEnabled = true
            source = factory.createAudioSource(MediaConstraints())
            microphone = factory.createAudioTrack("native-microphone", source).also { it.setEnabled(false); candidate.addTrack(it) }
            val offer = checkNotNull(sdp { candidate.createOffer(it, MediaConstraints()) })
            sdp { candidate.setLocalDescription(it, offer) }
            withTimeout(4000) { while (candidate.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) delay(10) }
            exchangeAttempted = true
            NativeMediaSignals.emit(serverId, NativeMediaStage.SUBSCRIPTION_REQUESTED, mapOf("role" to 0L))
            val answer = NativeHttpCredentials().post(url, token, mapOf("type" to "offer", "sdp" to candidate.localDescription.description,
                "eventId" to eventId, "serverCallId" to serverId, "negotiationId" to negotiationId))
            require(answer["type"] == "answer")
            val description = answer["sdp"] as? String ?: error("Missing SDP answer")
            sdp { candidate.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, description)) }
            check(!closed && peer === candidate)
            // These receiver wrappers live until peer disposal. The onTrack callback's
            // transceiver wrapper is transient and must not escape onto another executor.
            candidate.receivers.forEach { receiver -> (receiver.track() as? AudioTrack)?.let { track ->
                remote += track; track.setEnabled(true)
                emit(NativeMediaStage.REMOTE_TRACK_AVAILABLE)
                track.addSink(decode.decoderSink); track.addSink(decode.answerSink)
                emit(NativeMediaStage.REMOTE_SUBSCRIBED)
            } }
            check(remote.isNotEmpty()) { "SDP answer has no audio" }
            connected.await(); transportReady.await()
            check(!closed && peer === candidate)
            emit(NativeMediaStage.MEDIA_CONNECT_COMPLETED)
            // A silent remote remains a valid call. Observe warm readiness without requiring speech.
            withTimeoutOrNull(2000) { coroutineScope {
                launch { decode.awaitDecoded() }
                launch { heat.awaitPlaybackStarted(); heat.awaitMutedOutput() }
            } }
            check(!closed && peer === candidate)
            signal("prejoined")
            check(!closed && peer === candidate)
            heat.flushSilentWindow()
            prepared = true
            emit(NativeMediaStage.PREJOINED)
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
        active = true; held = false
        releaseOutput()
        if (!muted) startMicrophone() else stopMicrophone()
        scope.launch { runCatching { signal("answered") } }; Unit
    }
    override suspend fun setHeld(held: Boolean): Unit = withContext(worker) {
        check(!closed)
        this@WebRTCNativeSession.held = held
        if (active) {
            if (held) {
                pcm.disarm()
                recording?.setSpeakerMute(true); warmth.muteOutput()
                stopMicrophone()
            } else {
                releaseOutput()
                if (!muted) startMicrophone()
            }
        }
    }
    override suspend fun setMuted(muted: Boolean): Unit = withContext(worker) {
        check(!closed)
        this@WebRTCNativeSession.muted = muted
        if (active && !held) { if (muted) stopMicrophone() else startMicrophone() }
    }
    private fun releaseOutput() {
        val device = recording ?: error("Media not prepared")
        warmth.releaseOutput()
        device.setSpeakerMute(false)
        pcm.arm()
    }
    private suspend fun startMicrophone() {
        if (captureEnabled) return
        val device = recording ?: error("Media not prepared")
        val candidate = peer ?: error("Media not prepared")
        val ready = warmth.armCapture()
        device.setAudioRecordEnabled(true)
        warmth.publishRecordingEnabled()
        captureEnabled = true
        if (!pumpEnabled) { candidate.setAudioRecording(true); pumpEnabled = true }
        microphone?.setEnabled(true)
        withTimeout(1500) { ready.await() }
    }
    private suspend fun stopMicrophone() {
        microphone?.setEnabled(false)
        try {
            if (pumpEnabled) {
                peer?.setAudioRecording(false); pumpEnabled = false
                warmth.awaitPumpStopped()
            }
        } finally {
            recording?.setAudioRecordEnabled(false)
            captureEnabled = false
        }
    }
    private fun emit(stage: NativeMediaStage) = NativeMediaSignals.emit(serverId, stage)
    private suspend fun signal(state: String) {
        NativeHttpCredentials().post(url, token, mapOf("type" to "state", "state" to state, "eventId" to eventId,
            "serverCallId" to serverId, "negotiationId" to negotiationId))
    }
    private fun dispose() {
        prepared = false; active = false
        pcm.disarm()
        transportProbe?.cancel(); transportProbe = null
        remote.forEach { runCatching { it.removeSink(pcm.answerSink); it.removeSink(pcm.decoderSink) } }; remote.clear()
        runCatching { peer?.close(); peer?.dispose() }; peer = null
        runCatching { recording?.setAudioRecordEnabled(false) }; recording = null
        captureEnabled = false; pumpEnabled = false
        runCatching { microphone?.dispose() }; microphone = null
        runCatching { source?.dispose() }; source = null
        runCatching { owner?.release() }; owner = null
        pcm.close(); warmth.close()
    }
    override suspend fun close(reason: String) {
        if (closed) return
        try {
            withContext(worker) {
                if (closed) return@withContext
                closed = true
                val targetServer = serverId
                val targetUrl = url
                val authorization = token
                val terminal = if (exchangeAttempted) mapOf("type" to "state", "state" to reason,
                    "eventId" to eventId, "serverCallId" to serverId, "negotiationId" to negotiationId) else null
                scope.coroutineContext.cancelChildren()
                try { dispose() }
                finally {
                    emit(NativeMediaStage.CLOSED)
                    if (terminal != null) WebRTCTerminalSignal.send(targetServer, targetUrl, authorization, terminal)
                }
            }
        } finally { scope.cancel(); worker.close() }
    }
}
