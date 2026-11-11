package expo.modules.callkittelecom.conversations

/** Main-confined FCM data entry; reporting completes before media/JS preparation. */
class NativeCallPush internal constructor(
    private val incoming: suspend (Map<String, Any?>) -> Map<String, Any?>,
    private val remoteEnd: (Map<String, Any?>, () -> Unit) -> Unit,
    private val canWarm: (String) -> Boolean,
    private val shouldWarm: () -> Boolean,
    private val warmJavaScript: () -> Unit,
    private val signal: (String, NativeMediaStage, Map<String, Long>, String?) -> Unit = { id, stage, measurements, reason ->
        NativeMediaSignals.emit(id, stage, measurements, reason = reason)
    },
) {
    private class PendingAdmission(var ended: Boolean = false)
    private val admissions = mutableMapOf<String, MutableSet<PendingAdmission>>()
    constructor(engine: ConversationEngine, warmJavaScript: () -> Unit) : this(
        engine::incoming, engine::remoteEnd, engine::canWarmIncoming,
        { engine.policy.optString("type", "native") != "native" || engine.policy.optBoolean("prewarm", true) },
        warmJavaScript,
    )

    companion object {
        fun accepts(data: Map<String, String>) = data["messageType"] in
            setOf("incomingCall", "incoming_call", "callEnded", "call_ended")
    }

    suspend fun handle(data: Map<String, String>, highPriority: Boolean): Map<String, Any?>? =
        DefaultCallPushDecoder.decode(data)?.let { handle(it, highPriority) }

    internal suspend fun handle(
        decoded: DecodedCallPush,
        highPriority: Boolean,
        admission: CallPushAdmission = CallPushAdmission { CallPushDecision.Admit },
    ): Map<String, Any?>? {
        val push = when (decoded) {
            is DecodedCallPush.Ended -> {
                val payload = decoded.push.payload()
                remoteEnd(payload) {
                    admissions[decoded.push.serverCallId]?.forEach { it.ended = true }
                }
                return null
            }
            is DecodedCallPush.Incoming -> decoded.push
        }
        signal(push.serverCallId, NativeMediaStage.PUSH_RECEIVED,
            mapOf("highPriority" to if (highPriority) 1L else 0L), null)
        val pending = PendingAdmission()
        val waiting = admissions.getOrPut(push.serverCallId) { mutableSetOf() }
        waiting += pending
        try {
            when (val decision = admission.admit(push)) {
                CallPushDecision.Admit -> Unit
                is CallPushDecision.Refuse -> {
                    signal(push.serverCallId, NativeMediaStage.PUSH_REFUSED, emptyMap(), decision.reason)
                    return null
                }
            }
            if (pending.ended) {
                signal(push.serverCallId, NativeMediaStage.PUSH_REFUSED, emptyMap(), "callEndedBeforeAdmission")
                return null
            }
        } finally {
            waiting -= pending
            if (waiting.isEmpty() && admissions[push.serverCallId] === waiting) admissions.remove(push.serverCallId)
        }
        val outcome = incoming(push.payload())
        if (outcome["status"] == "reported" && canWarm(outcome["id"] as String) && shouldWarm()) {
            signal(push.serverCallId, NativeMediaStage.JS_WARM_STARTED, emptyMap(), null)
            warmJavaScript()
        }
        return outcome
    }
}
