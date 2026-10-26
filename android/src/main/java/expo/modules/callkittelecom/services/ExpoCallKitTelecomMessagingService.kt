package expo.modules.callkittelecom.services

import com.facebook.react.ReactApplication
import com.google.firebase.messaging.RemoteMessage
import expo.modules.callkittelecom.conversations.ConversationEngine
import expo.modules.notifications.service.ExpoFirebaseMessagingService
import kotlinx.coroutines.launch

/** High-priority FCM data messages enter Telecom natively before media or JS warmup. */
class ExpoCallKitTelecomMessagingService : ExpoFirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (!expo.modules.callkittelecom.conversations.NativeCallPush.accepts(message.data)) { super.onMessageReceived(message); return }
        val engine = ConversationEngine.get(applicationContext)
        engine.jobs.launch {
            try {
                expo.modules.callkittelecom.conversations.NativeCallPush(engine) {
                    (application as? ReactApplication)?.reactHost?.start()
                }.handle(message.data, message.originalPriority == RemoteMessage.PRIORITY_HIGH)
            } catch (_: Exception) { /* Malformed/stale pushes never crash the service. */ }
        }
    }
    override fun onNewToken(token: String) {
        val engine = ConversationEngine.get(applicationContext)
        engine.jobs.launch { engine.updatePushToken(token) }
        super.onNewToken(token)
    }
}
