package expo.modules.callkittelecom.conversations

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Implementations honor cancellation; failed prewarm releases resources and remains retryable. */
interface NativeMediaSession {
    suspend fun prewarm(payload: Map<String, Any?>)
    suspend fun activate()
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
    @Volatile private var closed = false
    @Volatile private var desiredMuted = false
    @Volatile private var activated = false

    @Suppress("UNCHECKED_CAST")
    private suspend fun prepare() = serial.withLock {
        currentCoroutineContext().ensureActive(); check(!closed)
        if (session == null) session = NativeMediaRegistry.factories[policy.optString("media", "webrtc")]?.invoke(context)
        currentCoroutineContext().ensureActive(); check(!closed)
        val pushed = payload["media"] as? Map<String, Any?>
        val fresh = (pushed?.get("expiresAt") as? Number)?.toLong()?.let { it > System.currentTimeMillis() / 1000 } == true
        val resolved = if (policy.opt("credentials") !is JSONObject && fresh) payload else {
            val source = policy.optJSONObject("credentials") ?: policy.optJSONObject("fallback")
            val fetcher = source?.optString("name")?.let { NativeMediaRegistry.fetchers[it] } ?: error("Native credentials unavailable")
            payload + ("media" to withTimeout(3000) { fetcher(payload) })
        }
        currentCoroutineContext().ensureActive(); check(!closed)
        session?.prewarm(resolved) ?: error("Native media adapter unavailable")
    }
    fun prewarm() {
        check(!closed)
        if (policy.optBoolean("prewarm", true)) preparation = operations.async { withTimeout(8000) { prepare() } }
    }
    fun answered(audioReady: suspend () -> Unit) {
        if (answerJob != null || closed) return
        answerJob = operations.launch {
            try {
                withTimeout(5000) {
                    try { preparation?.await() ?: error("No pre-warm") }
                    catch (_: Exception) { ensureActive(); check(!closed); prepare() }
                    audioReady()
                    serial.withLock {
                        ensureActive(); check(!closed)
                        session?.setMuted(desiredMuted)
                        session?.activate() ?: error("Native media adapter unavailable")
                        ensureActive(); check(!closed)
                        activated = true
                        if (desiredMuted) session?.setMuted(true)
                    }
                }
                withContext(Dispatchers.Main) { if (!closed) connected() }
            } catch (_: Exception) {
                if (isActive && !closed) withContext(Dispatchers.Main) { failed() }
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
    fun unmute(audioReady: suspend () -> Unit) {
        check(!closed)
        desiredMuted = false; unmuteJob?.cancel()
        if (!activated) return
        unmuteJob = operations.launch {
            try { withTimeout(3000) {
                audioReady()
                serial.withLock { ensureActive(); check(!closed); if (!desiredMuted) session?.setMuted(false) }
            } } catch (_: Exception) { if (isActive && !closed) withContext(Dispatchers.Main) { failed() } }
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
