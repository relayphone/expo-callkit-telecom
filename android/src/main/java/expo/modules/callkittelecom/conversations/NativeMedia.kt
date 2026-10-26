package expo.modules.callkittelecom.conversations

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONObject

/** Native implementations must honor coroutine cancellation and make close idempotent. */
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

/** Report precedes construction. Pre-join/retry and teardown share a native serial dispatcher. */
class NativeMediaController(private val context: Context, private val payload: Map<String, Any?>,
    private val policy: JSONObject, private val scope: CoroutineScope,
    private val failed: () -> Unit, private val connected: () -> Unit) {
    private var session: NativeMediaSession? = null
    private val mediaContext = Dispatchers.IO.limitedParallelism(1)
    private var preparation: Deferred<Unit>? = null
    private var answerJob: Job? = null
    @Volatile private var closed = false

    @Suppress("UNCHECKED_CAST")
    private suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        check(!closed)
        if (session == null) session = NativeMediaRegistry.factories[policy.optString("media", "webrtc")]?.invoke(context)
        currentCoroutineContext().ensureActive()
        check(!closed)
        val pushed = payload["media"] as? Map<String, Any?>
        val fresh = (pushed?.get("expiresAt") as? Number)?.toLong()?.let { it > System.currentTimeMillis() / 1000 } == true
        val resolved = if (policy.opt("credentials") !is JSONObject && fresh) payload else {
            val source = policy.optJSONObject("credentials") ?: policy.optJSONObject("fallback")
            val fetcher = source?.optString("name")?.let { NativeMediaRegistry.fetchers[it] }
                ?: error("Native credentials unavailable")
            payload + ("media" to withTimeout(3000) { fetcher(payload) })
        }
        currentCoroutineContext().ensureActive()
        check(!closed)
        session?.prewarm(resolved) ?: error("Native media adapter unavailable")
    }
    fun prewarm() { if (policy.optBoolean("prewarm", true)) preparation = scope.async(mediaContext) { prepare() } }
    fun answered(audioReady: suspend () -> Unit) {
        if (answerJob != null || closed) return
        answerJob = scope.launch {
            try {
                withContext(mediaContext) {
                    try { preparation?.await() ?: error("No pre-warm") }
                    catch (error: Exception) { ensureActive(); check(!closed); prepare() }
                    ensureActive(); check(!closed)
                    audioReady()
                    ensureActive(); check(!closed)
                    session?.activate() ?: error("Native media adapter unavailable")
                    ensureActive(); check(!closed)
                }
                if (!closed) connected()
            } catch (error: Exception) { if (error !is CancellationException && !closed) failed() }
        }
    }
    suspend fun mute(value: Boolean) { withTimeout(1000) { withContext(mediaContext) { session?.setMuted(value) } } }
    fun close(reason: String) {
        if (closed) return
        closed = true
        answerJob?.cancel(); preparation?.cancel()
        scope.launch(mediaContext) { withContext(NonCancellable) {
            withTimeoutOrNull(1500) { answerJob?.join(); preparation?.join() }
            runCatching { withTimeout(1500) { session?.close(reason) } }
        } }
    }
}
