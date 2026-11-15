package expo.modules.callkittelecom.conversations

import org.json.JSONArray
import org.json.JSONObject

/** Numeric-only diagnostics; caller-owned audio buffers and all qualification bounds are unchanged. */
internal class OutputConsumerTrace(private val nowNanos: () -> Long) {
    enum class Stage { WAIT, RTC_STATS, DRAIN, HTTP_ACK }
    private data class Span(val stage: Stage, val entered: Long, var exited: Long? = null, var completed: Boolean = false)
    private val lock = Any()
    private val spans = mutableListOf<Span>()
    private var dropped = 0
    private var captured = 0
    private var drained = 0
    private var captureStarted: Long? = null
    private var firstEnqueue: Long? = null
    private var enqueueEntered: Long? = null
    private var enqueueExited: Long? = null
    private var enqueueGap = 0L
    private var guard: JSONObject? = null

    fun begin() = synchronized(lock) {
        spans.clear(); dropped = 0; captured = 0; drained = 0
        captureStarted = null; firstEnqueue = null; enqueueEntered = null; enqueueExited = null; enqueueGap = 0
        guard = null
    }

    fun captureStarted() = synchronized(lock) { captureStarted = nowNanos() }

    fun enqueued(capturedFrames: Int, drainedFrames: Int, entered: Long, exited: Long) = synchronized(lock) {
        captured = capturedFrames; drained = drainedFrames
        if (firstEnqueue == null) firstEnqueue = entered
        enqueueEntered?.let { enqueueGap = maxOf(enqueueGap, entered - it) }
        enqueueEntered = entered; enqueueExited = exited
    }

    /** This is the existing 24,000-frame drain guard, retaining its original exception. */
    fun checkDrain(capturedFrames: Int, drainedFrames: Int) = synchronized(lock) {
        captured = capturedFrames; drained = drainedFrames
        if (capturedFrames - drainedFrames > 24000) {
            guard = counts().put("atNs", nowNanos().toString()).put("limitFrames", 24000)
            error("Output capture consumer fell behind")
        }
    }

    fun drained(capturedFrames: Int, drainedFrames: Int) = synchronized(lock) {
        captured = capturedFrames; drained = drainedFrames
    }

    suspend fun <T> measure(stage: Stage, work: suspend () -> T): T {
        val span = synchronized(lock) {
            Span(stage, nowNanos()).also {
                if (spans.size == 128) { spans.removeAt(0); dropped++ }
                spans += it
            }
        }
        try { return work().also { synchronized(lock) { span.completed = true } } }
        finally { synchronized(lock) { span.exited = nowNanos() } }
    }

    private fun counts() = JSONObject().put("capturedFrames", captured).put("drainedFrames", drained)
        .put("pendingFrames", captured - drained)

    fun snapshot(): JSONObject = synchronized(lock) {
        counts().put("clock", "android.elapsedRealtimeNanos").put("drainLimitFrames", 24000)
            .put("observedAtNs", nowNanos().toString()).put("captureStartedNs", captureStarted?.toString() ?: JSONObject.NULL)
            .put("enqueue", JSONObject().put("firstNs", firstEnqueue?.toString() ?: JSONObject.NULL)
                .put("enteredNs", enqueueEntered?.toString() ?: JSONObject.NULL)
                .put("exitedNs", enqueueExited?.toString() ?: JSONObject.NULL).put("largestGapNs", enqueueGap.toString()))
            .put("guard", guard ?: JSONObject.NULL).put("droppedSpans", dropped)
            .put("stages", JSONArray(spans.map {
                JSONObject().put("stage", it.stage.name).put("enteredNs", it.entered.toString())
                    .put("exitedNs", it.exited?.toString() ?: JSONObject.NULL).put("completed", it.completed)
            }))
    }
}
