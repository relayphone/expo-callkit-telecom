package expo.modules.callkittelecom.conversations

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.MainThread
import java.util.UUID
import kotlinx.coroutines.launch

/** Native notification navigation and one-use answer delivery; all access is on main. */
object ConversationNotificationIntents {
    const val EXTRA_CONVERSATION_ID = "expo.modules.callkittelecom.conversationId"
    const val EXTRA_ACTION = "expo.modules.callkittelecom.notificationAction"
    private const val EXTRA_TOKEN = "expo.modules.callkittelecom.answerToken"
    private const val ANSWER = "answer"
    private data class Delivery(val token: String = UUID.randomUUID().toString(), var claimed: Boolean = false)
    private val deliveries = mutableMapOf<String, Delivery>()

    @MainThread
    internal fun activity(context: Context, id: String, answer: Boolean = false,
        activityClass: String? = context.packageManager.getApplicationInfo(context.packageName,
            PackageManager.GET_META_DATA).metaData?.getString("ExpoCallKitTelecomCallActivity")): PendingIntent {
        val delivery = deliveries.getOrPut(id) { Delivery() }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, ConversationActivity::class.java)
        if (activityClass != null) {
            val name = when {
                activityClass.startsWith('.') -> context.packageName + activityClass
                '.' !in activityClass -> "${context.packageName}.$activityClass"
                else -> activityClass
            }
            intent.component = ComponentName(context.packageName, name)
        }
        intent.setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.Builder().scheme("voip-conversation").authority(context.packageName).appendPath(id)
                .appendPath(if (answer) ANSWER else "open").appendPath(delivery.token).build())
            .putExtra(EXTRA_CONVERSATION_ID, id)
        if (answer) intent.putExtra(EXTRA_ACTION, ANSWER).putExtra(EXTRA_TOKEN, delivery.token)
        else { intent.removeExtra(EXTRA_ACTION); intent.removeExtra(EXTRA_TOKEN) }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE or if (answer) PendingIntent.FLAG_ONE_SHOT else 0)
    }

    /** Called from the app Activity's native onCreate/onNewIntent, before JavaScript is ready. */
    @MainThread
    fun receive(context: Context, intent: Intent): Boolean {
        if (intent.getStringExtra(EXTRA_ACTION) != ANSWER) return false
        val id = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return false
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        val delivery = deliveries[id] ?: return false
        if (delivery.token != token || delivery.claimed) return false
        delivery.claimed = true
        intent.removeExtra(EXTRA_ACTION); intent.removeExtra(EXTRA_TOKEN)
        val engine = ConversationEngine.get(context)
        engine.jobs.launch { engine.notificationCommand(id, SystemCommandAction.ANSWER) }
        return true
    }

    @MainThread
    internal fun retire(id: String) { deliveries.remove(id) }
}
