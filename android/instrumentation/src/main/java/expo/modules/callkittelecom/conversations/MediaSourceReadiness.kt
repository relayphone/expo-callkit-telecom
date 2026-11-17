package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** First-buffer readiness is separate from the subsequent source continuity assertions. */
internal class MediaSourceReadiness(private val nowNanos: () -> Long) {
    private val lock = Any()
    private var publication: Long? = null
    private var pump: Long? = null
    private var firstBuffer: Long? = null
    private val pending = ArrayDeque<Long>()
    private var timedOut = false

    fun published(atNanos: Long) = synchronized(lock) {
        if (publication == null) {
            publication = atNanos
            firstBuffer = pending.firstOrNull { it >= atNanos }
            pending.clear()
        }
    }

    fun pumpStarted(atNanos: Long) = synchronized(lock) { if (pump == null) pump = atNanos }

    fun buffer(atNanos: Long, frames: Int, rate: Int, bytesRead: Int, muted: Boolean?) = synchronized(lock) {
        if (frames != 480 || rate != 48000 || bytesRead != 0 || muted != true || firstBuffer != null) return@synchronized
        val published = publication
        if (published == null) {
            check(pending.size < 4096) { "Source readiness observations overflowed" }
            pending.addLast(atNanos)
        } else if (atNanos >= published) firstBuffer = atNanos
    }

    suspend fun awaitReady(pause: suspend () -> Unit = { delay(10) }) {
        val published = synchronized(lock) { checkNotNull(publication) }
        val deadline = published + START_BUDGET_NANOS
        while (true) {
            val ready = synchronized(lock) { firstBuffer?.let { it <= deadline && pump != null } == true }
            if (ready) return
            val remaining = deadline - nowNanos()
            if (remaining <= 0 || withTimeoutOrNull((remaining + 999_999) / 1_000_000) { pause(); true } != true) {
                synchronized(lock) { timedOut = true }
                throw AssertionError("source-start-timeout")
            }
        }
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        val latency = firstBuffer?.let { buffer -> publication?.let { buffer - it } }
        val publicationToPump = pump?.let { started -> publication?.let { started - it } }
        val pumpToFirstBuffer = firstBuffer?.let { buffer -> pump?.let { buffer - it } }
        JSONObject().put("clock", "android.elapsedRealtimeNanos").put("budgetMs", START_BUDGET_NANOS / 1_000_000)
            .put("publicationNs", publication?.toString() ?: JSONObject.NULL)
            .put("pumpStartedNs", pump?.toString() ?: JSONObject.NULL)
            .put("firstBufferNs", firstBuffer?.toString() ?: JSONObject.NULL)
            .put("startLatencyNs", latency?.toString() ?: JSONObject.NULL)
            .put("startLatencyMs", latency?.div(1_000_000.0) ?: JSONObject.NULL)
            .put("publicationToPumpNs", publicationToPump?.toString() ?: JSONObject.NULL)
            .put("publicationToPumpMs", publicationToPump?.div(1_000_000.0) ?: JSONObject.NULL)
            .put("pumpToFirstBufferNs", pumpToFirstBuffer?.toString() ?: JSONObject.NULL)
            .put("pumpToFirstBufferMs", pumpToFirstBuffer?.div(1_000_000.0) ?: JSONObject.NULL)
            .put("timedOut", timedOut)
    }

    private companion object {
        const val START_BUDGET_NANOS = 3_300_000_000L
    }
}
