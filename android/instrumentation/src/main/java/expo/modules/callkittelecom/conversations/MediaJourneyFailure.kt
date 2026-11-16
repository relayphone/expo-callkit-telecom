package expo.modules.callkittelecom.conversations

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Observations only: the source-progress assertion and its sampling delay remain at the call site. */
internal class MediaSourceProgress {
    private val samples = ArrayDeque<JSONObject>()
    fun record(beforeNs: Long, afterNs: Long, beforeFrames: Long, afterFrames: Long) {
        if (samples.size == 8) samples.removeFirst()
        samples.addLast(JSONObject().put("beforeNs", beforeNs.toString()).put("afterNs", afterNs.toString())
            .put("elapsedNs", (afterNs - beforeNs).toString())
            .put("beforeFrames", beforeFrames).put("afterFrames", afterFrames))
    }
    fun snapshot(): JSONObject = JSONObject().put("clock", "android.elapsedRealtimeNanos")
        .put("samples", JSONArray(samples.toList()))
}

/** Flush the original assertion before optional diagnostics, then rethrow that same instance. */
internal suspend fun rethrowMediaJourneyFailure(file: File, evidence: JSONObject, error: Throwable,
    test: String, phase: String, pid: Int, forced: Boolean, progress: MediaSourceProgress,
    receiver: JSONObject, enrich: suspend () -> Unit): Nothing {
    try {
        val message = if (error is AssertionError) error.message.orEmpty() else "Non-assertion failure"
        val original = JSONObject().put("type", error.javaClass.simpleName).put("message", message.take(8192))
            .put("messageTruncated", message.length > 8192)
        evidence.put("passed", false).put("step", phase).put("test", test).put("pid", pid)
            .put("forcedFailure", forced).put("originalFailure", original)
            .put("failure", JSONArray().put(original)).put("sourceProgress", progress.snapshot())
            .put("receiverWait", receiver).put("gateTrace", JSONObject())
        writeMediaEvidence(file, evidence.toString())
    } catch (diagnostic: Throwable) { if (diagnostic !== error) error.addSuppressed(diagnostic) }
    try { enrich() }
    catch (diagnostic: Throwable) { if (diagnostic !== error) error.addSuppressed(diagnostic) }
    throw error
}
