package expo.modules.callkittelecom.services

import com.facebook.react.ReactApplication
import com.google.firebase.messaging.RemoteMessage
import expo.modules.callkittelecom.conversations.ConversationEngine
import expo.modules.notifications.service.ExpoFirebaseMessagingService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** High-priority FCM data messages enter Telecom natively before media or JS warmup. */
class ExpoCallKitTelecomMessagingService : ExpoFirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["messageType"]
        if (type !in setOf("incomingCall", "callEnded", "incoming_call", "call_ended")) { super.onMessageReceived(message); return }
        val raw = message.data[type] ?: return
        val payload = runCatching { toMap(JSONObject(raw)) }.getOrNull() ?: return
        val engine = ConversationEngine.get(applicationContext)
        engine.jobs.launch {
            try {
            if (type == "incomingCall" || type == "incoming_call") {
                val result = engine.incoming(payload)
                if (result["status"] == "reported") (application as? ReactApplication)?.reactHost?.start()
            } else engine.remoteEnd(payload)
            } catch (_: Exception) { /* Malformed/stale pushes never crash the service. */ }
        }
    }
    override fun onNewToken(token: String) {
        val engine = ConversationEngine.get(applicationContext)
        engine.jobs.launch { engine.updatePushToken(token) }
        super.onNewToken(token)
    }
    private fun json(value: Any?): Any? = when(value) {
        JSONObject.NULL -> null
        is JSONObject -> toMap(value)
        is JSONArray -> (0 until value.length()).map { json(value.get(it)) }
        else -> value
    }
    private fun toMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { json(value.get(it)) }
}
