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

/** Replace any earlier journey's capture with the failing claim's own bounded callback trace. */
internal fun retainMediaClaimCapture(file: File, original: Throwable, claimId: String, claimDelayMillis: Long,
    capture: () -> JSONObject) {
    try {
        require(claimId.matches(Regex("hooks-[0-9]+")))
        val trace = capture().put("claimId", claimId).put("claimDelayMillis", claimDelayMillis)
        val callbacks = trace.getJSONArray("callbacks")
        trace.put("callbacks", JSONArray((0 until callbacks.length()).filter {
            it < 16 || it >= callbacks.length() - 240
        }.map { callbacks.get(it) })).put("droppedCallbacks", maxOf(0, callbacks.length() - 256))
        val previous = if (file.isFile) JSONObject(file.readText()).optJSONArray("claimFailures") else null
        val count = previous?.length() ?: 0
        val failures = JSONArray((maxOf(0, count - 3) until count).map { previous!!.get(it) }).put(trace)
        writeMediaEvidence(file, JSONObject(trace.toString()).put("claimFailures", failures).toString())
    } catch (diagnostic: Throwable) { if (diagnostic !== original) original.addSuppressed(diagnostic) }
}

/** Later qualification writes retain the bounded captures belonging to earlier failed claims. */
internal fun writeMediaCaptureEvidence(file: File, capture: JSONObject) {
    val failures = if (file.isFile) JSONObject(file.readText()).optJSONArray("claimFailures") else null
    if (failures != null) capture.put("claimFailures", failures)
    writeMediaEvidence(file, capture.toString())
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
