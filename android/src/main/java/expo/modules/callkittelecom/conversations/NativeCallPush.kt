package expo.modules.callkittelecom.conversations

import org.json.JSONArray
import org.json.JSONObject

/** Shared production FCM data entry; reporting completes before media/JS preparation. */
class NativeCallPush(private val engine: ConversationEngine, private val warmJavaScript: () -> Unit) {
    companion object {
        fun accepts(data: Map<String, String>) = data["messageType"] in
            setOf("incomingCall", "incoming_call", "callEnded", "call_ended")
        private fun value(input: Any?): Any? = when (input) {
            JSONObject.NULL -> null
            is JSONObject -> input.keys().asSequence().associateWith { value(input.get(it)) }
            is JSONArray -> (0 until input.length()).map { value(input.get(it)) }
            else -> input
        }
    }
    @Suppress("UNCHECKED_CAST")
    suspend fun handle(data: Map<String, String>, highPriority: Boolean): Map<String, Any?>? {
        if (!accepts(data)) return null
        val type = data["messageType"] ?: return null
        val raw = data[type] ?: return null
        val payload = value(JSONObject(raw)) as Map<String, Any?>
        if (type == "callEnded" || type == "call_ended") { engine.remoteEnd(payload); return null }
        val serverId = payload["serverCallId"] as? String ?: return null
        NativeMediaSignals.emit(serverId, NativeMediaStage.PUSH_RECEIVED, mapOf("highPriority" to if (highPriority) 1L else 0L))
        val outcome = engine.incoming(payload)
        if (outcome["status"] == "reported" &&
            (engine.policy.optString("type", "native") != "native" || engine.policy.optBoolean("prewarm", true))) {
            NativeMediaSignals.emit(serverId, NativeMediaStage.JS_WARM_STARTED)
            warmJavaScript()
        }
        return outcome
    }
}
