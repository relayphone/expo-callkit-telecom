package expo.modules.callkittelecom.services

import com.google.firebase.messaging.RemoteMessage
import expo.modules.callkittelecom.conversations.CallPushIntake
import expo.modules.notifications.service.ExpoFirebaseMessagingService

/** High-priority FCM data messages enter Telecom natively before media or JS warmup. */
class ExpoCallKitTelecomMessagingService : ExpoFirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (!CallPushIntake.handle(applicationContext, message)) super.onMessageReceived(message)
    }
    override fun onNewToken(token: String) {
        CallPushIntake.newToken(applicationContext, token)
        super.onNewToken(token)
    }
}
