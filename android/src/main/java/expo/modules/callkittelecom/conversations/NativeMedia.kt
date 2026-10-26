package expo.modules.callkittelecom.conversations

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONObject

/** Native application extension points. Register in Application.onCreate, before a push can arrive. */
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

/** One pre-join and at most one answer-time retry; cancellation always releases the native session. */
class NativeMediaController(context: Context, private val payload: Map<String, Any?>, private val policy: JSONObject,
                            private val scope: CoroutineScope, private val failed: () -> Unit, private val connected: () -> Unit) {
    private val session = NativeMediaRegistry.factories[policy.optString("media", "webrtc")]?.invoke(context)

    private val mediaContext = Dispatchers.IO.limitedParallelism(1)
    private var preparation: Deferred<Unit>? = null
    private var closed = false
    fun prewarm() {
        if (policy.optBoolean("prewarm", true)) preparation = scope.async(mediaContext) { session?.prewarm(payload) ?: error("Native media adapter unavailable") }
    }
    fun answered(audioReady: suspend () -> Unit) {
        scope.launch {
            try {
                withContext(mediaContext) {
                try { preparation?.await() ?: error("No pre-warm") }
                catch (_: Exception) { check(!closed); session?.prewarm(payload) ?: error("Native media adapter unavailable") }
                check(!closed)
                audioReady()
                session?.activate() ?: error("Native media adapter unavailable")
                }
                connected()
            } catch (_: Exception) { if (!closed) failed() }
        }
    }
    suspend fun mute(value: Boolean) { withContext(mediaContext) { session?.setMuted(value) } }
    fun close(reason: String) {
        if (closed) return
        closed = true
        preparation?.cancel()
        scope.launch(mediaContext) { withContext(NonCancellable) { runCatching { withTimeout(1500) { session?.close(reason) } } } }
    }
}
