package expo.modules.callkittelecom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import expo.modules.callkittelecom.conversations.ConversationNotificationIntents
import expo.modules.core.interfaces.Package
import expo.modules.core.interfaces.ReactActivityLifecycleListener

/** Expo discovers this package and installs the listener independently of the JS module. */
class ExpoCallKitTelecomPackage : Package {
    override fun createReactActivityLifecycleListeners(activityContext: Context?): List<ReactActivityLifecycleListener> =
        listOf(object : ReactActivityLifecycleListener {
            private var context: Context? = activityContext?.applicationContext
            override fun onCreate(activity: Activity, savedInstanceState: Bundle?) {
                context = activity.applicationContext
                ConversationNotificationIntents.receive(activity, activity.intent)
            }
            override fun onNewIntent(intent: Intent): Boolean =
                context?.let { ConversationNotificationIntents.receive(it, intent) } ?: false
        })
}
