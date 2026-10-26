package expo.modules.callkittelecom.conversations

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Implementations honor cancellation; failed prewarm releases resources and remains retryable. */
interface NativeMediaSession {
    val isPrepared: Boolean get() = true
    fun setFailureListener(listener: () -> Unit) {}
    suspend fun prewarm(payload: Map<String, Any?>)
    suspend fun activate()
    /** Suspend local capture AND remote playback; resume only after native audio authority. */
    suspend fun setHeld(held: Boolean)
    /** Set desired mute state without starting inactive media. */
    suspend fun setMuted(muted: Boolean)
    suspend fun close(reason: String)
}
object NativeMediaRegistry {
    val fetchers = ConcurrentHashMap<String, suspend (Map<String, Any?>) -> Map<String, Any?>>()
    val factories = ConcurrentHashMap<String, (Context) -> NativeMediaSession>()
}

/** Native work has one lifetime. Cleanup can run even after the caller's scope is cancelled. */
internal class NativeMediaController(private val context: Context, private val payload: Map<String, Any?>,
    private val policy: JSONObject, private val scope: CoroutineScope,
    private val failed: () -> Unit, private val connected: () -> Unit) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val operations = CoroutineScope(lifetime + dispatcher)
    private val serial = Mutex()
    private var session: NativeMediaSession? = null
    private var preparation: Deferred<Unit>? = null
    private var answerJob: Job? = null
    private var unmuteJob: Job? = null
    private var resumeJob: Job? = null
    @Volatile private var closed = false
    @Volatile private var desiredMuted = false
    @Volatile private var activated = false
    @Volatile private var activating = false
    @Volatile private var held = false
    private var retried = false

    @Suppress("UNCHECKED_CAST")
    private suspend fun prepare() = serial.withLock {
        currentCoroutineContext().ensureActive(); check(!closed)
        if (session == null) {
            val adapter = policy.optString("media", "webrtc")
            session = NativeMediaRegistry.factories[adapter]?.invoke(context)
                ?: when (adapter) {
                    "livekit" -> LiveKitNativeSession(context, payload["serverCallId"] as? String ?: error("Invalid media invitation"))
                    "webrtc" -> WebRTCNativeSession(context, payload["serverCallId"] as? String ?: error("Invalid media invitation"))
                    else -> null
                }
        }
        session?.setFailureListener {
            operations.launch(Dispatchers.Main) { if (!closed) failed() }
        }
        currentCoroutineContext().ensureActive(); check(!closed)
        val pushed = payload["media"] as? Map<String, Any?>
        val fresh = (pushed?.get("expiresAt") as? Number)?.toLong()?.let { it > System.currentTimeMillis() / 1000 } == true
        val compatiblePush = policy.optString("media", "webrtc") == "livekit" && pushed?.get("provider") == "livekit"
        // WebRTC sessions own native negotiation; only LiveKit has a push credential wire shape.
        val nativeNegotiation = policy.optString("media", "webrtc") == "webrtc" && NativeMediaRegistry.factories.containsKey("webrtc") &&
            policy.opt("credentials") !is JSONObject && policy.opt("fallback") !is JSONObject
        val resolved = if (nativeNegotiation || (policy.opt("credentials") !is JSONObject && fresh && compatiblePush)) payload else {
            val source = policy.optJSONObject("credentials") ?: policy.optJSONObject("fallback")
            val credentials = withTimeout(3000) {
                if (source?.optString("type") == "http") NativeHttpCredentials().fetch(payload, source)
                else {
                    val fetcher = source?.optString("name")?.let { NativeMediaRegistry.fetchers[it] }
                        ?: error("Native credentials unavailable")
                    fetcher(payload)
                }
            }
            payload + ("media" to credentials)
        }
        currentCoroutineContext().ensureActive(); check(!closed)
        session?.prewarm(resolved) ?: error("Native media adapter unavailable")
    }
    fun prewarm() {
        check(!closed)
        if (policy.optBoolean("prewarm", true)) preparation = operations.async {
            (payload["serverCallId"] as? String)?.let { NativeMediaSignals.emit(it, NativeMediaStage.PREWARM_STARTED) }
            withTimeout(8000) { prepare() }
        }
    }
    fun answered(audioReady: suspend () -> Unit) {
        if (answerJob != null || closed) return
        answerJob = operations.launch {
            try {
                withTimeout(5000) {
                    try {
                        preparation?.await() ?: error("No pre-warm")
                        check(session?.isPrepared != false) { "Pre-warm disconnected" }
                    }
                    catch (error: Exception) {
                        ensureActive(); check(!closed)
                        if (retried) throw error
                        retried = true
                        preparation = operations.async { withTimeout(8000) { prepare() } }
                        preparation!!.await()
                    }
                    audioReady()
                    serial.withLock {
                        ensureActive(); check(!closed && !held)
                        session?.setHeld(false)
                        session?.setMuted(desiredMuted)
                        activating = true
                        try { session?.activate() ?: error("Native media adapter unavailable") }
                        finally { activating = false }
                        ensureActive(); check(!closed)
                        activated = true
                        session?.setMuted(desiredMuted)
                    }
                }
                withContext(Dispatchers.Main) { if (!closed) connected() }
            } catch (_: Exception) {
                if (isActive && !closed && !held) withContext(Dispatchers.Main) { if (!closed) failed() }
            }
        }
    }
    suspend fun mute() {
        check(!closed)
        desiredMuted = true; unmuteJob?.cancel()
        if (!activated) return
        val work = operations.async { withTimeout(1000) { serial.withLock { check(!closed); session?.setMuted(true) } } }
        try { work.await() } finally { work.cancel() }
    }
    fun clearMute() { check(!closed); desiredMuted = false; unmuteJob?.cancel() }
    suspend fun pause() {
        check(!closed)
        held = true; answerJob?.cancel(); answerJob = null; unmuteJob?.cancel(); resumeJob?.cancel()
        if (!activated && !activating) return
        val work = operations.async { withTimeout(1000) { serial.withLock { check(!closed); session?.setHeld(true) } } }
        try { work.await() } finally { work.cancel() }
    }
    fun resume(audioReady: suspend () -> Unit) {
        check(!closed)
        held = false
        if (!activated) { answerJob = null; answered(audioReady); return }
        resumeJob?.cancel()
        resumeJob = operations.launch {
            try { withTimeout(3000) {
                audioReady()
                serial.withLock { ensureActive(); check(!closed && !held); session?.setHeld(false); session?.setMuted(desiredMuted) }
            } } catch (_: Exception) { if (isActive && !closed && !held) withContext(Dispatchers.Main) { if (!closed) failed() } }
        }
    }
    fun unmute(audioReady: suspend () -> Unit) {
        check(!closed)
        clearMute()
        if (!activated || held) return
        unmuteJob = operations.launch {
            try { withTimeout(3000) {
                audioReady()
                serial.withLock { ensureActive(); check(!closed); if (!desiredMuted) session?.setMuted(false) }
            } } catch (_: Exception) { if (isActive && !closed && !held) withContext(Dispatchers.Main) { if (!closed) failed() } }
        }
    }
    fun close(reason: String) {
        if (closed) return
        closed = true
        lifetime.cancel()
        // Independent cleanup ownership is intentional; no cancelled parent can skip it.
        CoroutineScope(dispatcher + SupervisorJob()).launch {
            withTimeoutOrNull(1500) { lifetime.join() }
            runCatching { withTimeout(1500) { serial.withLock { session?.close(reason) } } }
            coroutineContext[Job]?.cancel()
        }
    }
}
