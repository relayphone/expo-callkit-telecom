package expo.modules.callkittelecom.conversations

import org.json.JSONObject
import org.json.JSONException
import java.io.IOException

internal const val MEDIA_XML_TRACE_BYTES = 8192
internal const val MEDIA_XML_TRACE_TRUNCATED = "[TRUNCATED media qualification trace]"

internal fun mediaQualificationTrace(
    consumer: JSONObject? = null,
    receiver: JSONObject? = null,
    fixture: JSONObject? = null,
    publications: MediaEvidencePublications = MediaEvidencePublications(),
    claim: JSONObject? = null,
): String {
    val lines = mutableListOf("[mediaQualificationTrace v1]", "hostJsonCollection=not_started",
        "jsonPublications=${publications.snapshot()}")
    val histories = mutableListOf<String>()
    var sourceTruncated = false
    val claimTrace = claim?.takeIf { it.opt("clock") == "android.elapsedRealtimeNanos" }
    if (claimTrace != null) {
        lines += "claim.clock=android.elapsedRealtimeNanos"
        lines += "claim.counts=${numericFields(claimTrace, "claimDelayMillis", "grant", "muteBeforeAnswer", "creations",
            "clients", "clientObservationNs", "truncatedEvents")}"
        val negotiations = claimTrace.optJSONObject("signaling")?.optJSONArray("negotiations")
        sourceTruncated = (negotiations?.length() ?: 0) > 8 || claimTrace.optInt("truncatedEvents") > 0
        for (index in 0 until minOf(negotiations?.length() ?: 0, 8)) {
            val connection = negotiations?.optJSONObject(index)?.takeIf { it.opt("clock") == "python.time.monotonic_ns" } ?: continue
            lines += "claim.signaling.clock=python.time.monotonic_ns"
            lines += "claim.connection=${numericFields(connection, "connection", "publisherOffers", "subscriberAnswers",
                "audioPublications", "audioPublisherOffers", "publisherIceRestarts", "decodeErrors", "truncatedEvents")}"
            val events = connection.optJSONArray("events")
            sourceTruncated = sourceTruncated || (events?.length() ?: 0) > 16 || connection.optInt("truncatedEvents") > 0
            for (eventIndex in 0 until minOf(events?.length() ?: 0, 16)) {
                histories += "claim.negotiation=${numericFields(events?.optJSONObject(eventIndex), "atNs", "direction", "kind",
                    "audioSections", "dataSections", "audioSending", "audioSsrcs", "negotiationId", "iceChanged", "muted")
                    .put("connection", numericFields(connection, "connection").opt("connection"))}"
            }
        }
        val peer = claimTrace.optJSONObject("signaling")?.optJSONObject("receiver")
            ?.takeIf { it.opt("clock") == "python.time.monotonic_ns" }
        if (peer != null) {
            lines += "claim.receiver.clock=python.time.monotonic_ns"
            lines += "claim.receiver=${numericFields(peer, "startedNs", "firstPcmNs", "frames", "firstMarkerReceivedNs", "firstMarkerDetectedNs")}"
        }
        val signals = claimTrace.optJSONArray("signals")
        sourceTruncated = sourceTruncated || (signals?.length() ?: 0) > 128
        val allowed = NativeMediaStage.entries.map { it.value }.toSet()
        val retained = (0 until minOf(signals?.length() ?: 0, 128)).mapNotNull { index ->
            val signal = signals?.optJSONObject(index)?.takeIf { it.opt("stage") in allowed } ?: return@mapNotNull null
            numericFields(signal, "atNs", "permissionUnavailable").put("stage", signal.opt("stage"))
                .put("measurements", numericFields(signal.optJSONObject("measurements"), "localMicrophones", "localPublications",
                    "frames", "sampleRate", "channels", "muted", "role", "sampled", "pollIntervalMs", "statsCacheMs", "requestNanos",
                    "operationId", "startedNs", "durationNs", "completed", "phase", "timeout"))
                .apply {
                    val reason = signal.opt("reason")
                    if (reason in setOf("PUBLISH_PERMISSION_NOT_GRANTED", "PREJOIN_CLOSE_FAILED")) put("reason", reason)
                }
        }
        // Keep failure details and cleanup durations ahead of optional history when the trace is bounded.
        retained.filter { it.opt("stage") == NativeMediaStage.ANSWER_FAILED.value ||
            it.opt("stage") == NativeMediaStage.RELEASE_COMPLETED.value ||
            it.optString("stage").startsWith("close") }.forEach { lines += "claim.retirement=$it" }
        retained.groupBy { it.getString("stage") }.forEach { (stage, spans) ->
            lines += "claim.stage=${JSONObject().put("stage", stage).put("count", spans.size)
                .put("firstNs", spans.first().opt("atNs")).put("lastNs", spans.last().opt("atNs"))}"
        }
        retained.asReversed().forEach { histories += "claim.signal=$it" }
    }
    val device = consumer?.takeIf { it.opt("clock") == "android.elapsedRealtimeNanos" }
    val server = fixture?.takeIf { it.opt("clock") == "host.monotonic_ns" }
    lines += "device.clock=android.elapsedRealtimeNanos"
    lines += "device.guard=${numericFields(device?.optJSONObject("guard"), "atNs", "limitFrames", "pendingFrames", "capturedFrames", "drainedFrames")}"
    lines += "device.counts=${numericFields(device, "drainLimitFrames", "pendingFrames", "capturedFrames", "drainedFrames", "observedAtNs", "captureStartedNs", "droppedSpans")}"
    lines += "receiver.clock=android.elapsedRealtimeNanos"
    val receiverTrace = receiver?.takeIf { it.opt("clock") == "android.elapsedRealtimeNanos" }
    lines += "receiver=${numericFields(receiverTrace,
        "budgetMs", "startedNs", "prejoinedObservedNs", "lastReadStartedNs", "lastReadCompletedNs", "completedNs", "failureObservedNs", "reads", "lastRunning")}"
    val signals = receiverTrace?.optJSONArray("signals")
    sourceTruncated = sourceTruncated || (signals?.length() ?: 0) > 128
    val allowedSignals = setOf("mediaConnectStarted", "mediaConnectCompleted", "microphonePublishStarted",
        "microphonePublished", "prejoined", "subscriptionRequested", "remoteSubscribed")
    val receiverSignals = (0 until minOf(signals?.length() ?: 0, 128)).mapNotNull { index ->
        val signal = signals?.optJSONObject(index) ?: return@mapNotNull null
        if (signal.opt("stage") in allowedSignals)
            numericFields(signal, "atNs").put("stage", signal.opt("stage")) else null
    }
    receiverSignals.groupBy { it.getString("stage") }.values.forEach { spans ->
        lines += "receiver.first=${spans.first()}"
        if (spans.size > 1) lines += "receiver.last=${spans.last()}"
    }
    receiverSignals.forEach { histories += "receiver.signal=$it" }
    for ((domain, trace) in listOf("device" to device, "fixture" to server)) {
        lines += "$domain.enqueue=${numericFields(trace?.optJSONObject("enqueue"), "firstNs", "enteredNs", "exitedNs", "largestGapNs")}"
        if (trace == null) { lines += "$domain.trace=unobserved"; continue }
        lines += "$domain.clock=${trace.opt("clock")}"
        lines += "$domain.counts=${numericFields(trace, "frames", "observedAtNs", "droppedSpans")}"
        val stages = trace.optJSONArray("stages")
        sourceTruncated = sourceTruncated || (stages?.length() ?: 0) > 128
        val allowed = setOf("WAIT", "RTC_STATS", "DRAIN", "HTTP_ACK", "ENQUEUE", "SCORE", "HTTP_RESPONSE")
        val spans = (0 until minOf(stages?.length() ?: 0, 128)).mapNotNull { index ->
            stages?.optJSONObject(index)?.takeIf { it.opt("stage") in allowed }?.let { span ->
                numericFields(span, "enteredNs", "exitedNs", "queuedNs", "completed", "firstFrame", "frame")
                    .put("stage", span.opt("stage"))
            }
        }
        // Summaries for both clocks precede bounded history, so truncation keeps the slow/last stages.
        fun duration(span: JSONObject): Long = (span.optString("exitedNs").toLongOrNull()
            ?: trace.optString("observedAtNs").toLongOrNull() ?: 0) - (span.optString("enteredNs").toLongOrNull() ?: 0)
        spans.maxByOrNull(::duration)?.let { lines += "$domain.slowest=$it" }
        spans.groupBy { it.getString("stage") }.values.forEach { lines += "$domain.latest=${it.last()}" }
        spans.asReversed().forEach { histories += "$domain.stage=$it" }
    }
    val text = (lines + histories).joinToString("\n")
    // All copied values are numeric/boolean/null or fixed ASCII labels; bytes equal characters.
    return if (text.length <= MEDIA_XML_TRACE_BYTES && !sourceTruncated) text
        else text.take(MEDIA_XML_TRACE_BYTES - MEDIA_XML_TRACE_TRUNCATED.length) + MEDIA_XML_TRACE_TRUNCATED
}

private fun numericFields(source: JSONObject?, vararg names: String): JSONObject = JSONObject().apply {
    for (name in names) {
        val value = source?.opt(name)
        if (value == JSONObject.NULL || value is Boolean || value is Int && value >= 0 || value is Long && value >= 0 ||
            value is String && Regex("[0-9]{1,24}").matches(value)) put(name, value)
    }
}

internal fun mediaQualificationAssertion(error: Throwable, snapshot: () -> String): AssertionError =
    AssertionError("$error\n${runCatching(snapshot).getOrDefault("[mediaQualificationTrace unavailable]")}", error)

internal class MediaEvidencePublications {
    enum class File { RESULTS, CAPTURE }
    private enum class State { NOT_ATTEMPTED, PUBLISHED, IO_ERROR, SECURITY_ERROR, SERIALIZATION_ERROR, OTHER_ERROR }
    private val states = File.entries.associateWith { State.NOT_ATTEMPTED }.toMutableMap()
    fun write(file: File, operation: () -> Unit) {
        try { operation(); states[file] = State.PUBLISHED }
        catch (error: Throwable) {
            states[file] = when (error) {
                is IOException -> State.IO_ERROR
                is SecurityException -> State.SECURITY_ERROR
                is JSONException -> State.SERIALIZATION_ERROR
                else -> State.OTHER_ERROR
            }
            throw error
        }
    }
    fun snapshot(): JSONObject = JSONObject().apply { states.forEach { (file, state) -> put(file.name, state.name) } }
}
