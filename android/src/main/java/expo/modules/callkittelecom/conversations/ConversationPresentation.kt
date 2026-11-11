package expo.modules.callkittelecom.conversations

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** The notification's ringing or ongoing presentation. */
enum class CallPhase { RINGING, ONGOING }

/** App content for the library-owned CallStyle notification. Null fields keep the defaults. */
data class CallNotificationContent(
    val title: String? = null,
    val avatar: Icon? = null,
    /** Used only while ringing. The app owns the target activity. */
    val fullScreenIntent: PendingIntent? = null,
    val contentIntent: PendingIntent? = null,
    /** CallStyle shows at most one extra action while ringing, two while ongoing. */
    val extraActions: List<NotificationCompat.Action> = emptyList(),
    /** An existing app-created channel; its sound and vibration settings are preserved. */
    val channelId: String? = null,
)

/** Runs on main; return immediately without I/O. The public snapshot entry contains no media grants. */
fun interface CallNotificationCustomizer {
    fun content(conversation: Map<String, Any?>, phase: CallPhase): CallNotificationContent
}

/** Customizes subsequent notifications while the library retains CallStyle, service and controls. */
object ConversationPresentation {
    @Volatile private var customizer: CallNotificationCustomizer? = null

    /** Configure from Application.onCreate; null restores the library's content. */
    @JvmStatic fun configure(customizer: CallNotificationCustomizer?) { this.customizer = customizer }

    internal fun resolve(conversation: Map<String, Any?>, phase: CallPhase,
                         defaults: CallNotificationContent): CallNotificationContent {
        val content = runCatching { customizer?.content(conversation, phase) }.getOrNull() ?: CallNotificationContent()
        return content.copy(
            title = content.title ?: defaults.title,
            avatar = content.avatar ?: defaults.avatar,
            fullScreenIntent = if (phase == CallPhase.RINGING) content.fullScreenIntent ?: defaults.fullScreenIntent else null,
            contentIntent = content.contentIntent ?: defaults.contentIntent,
            channelId = content.channelId ?: defaults.channelId,
        )
    }
}

/** Native controls for an app-owned call activity. Safe from any thread; effects run on main. */
object ConversationControls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Answer through the native action handler with origin `app`. */
    @JvmStatic fun answer(context: Context, conversationId: String) {
        perform(context) { it.join(conversationId, NativeActionOrigin.APP) }
    }

    /** Decline a ringing call through the native action handler with origin `app`. */
    @JvmStatic fun decline(context: Context, conversationId: String) { end(context, conversationId) }

    /** End through the native action handler with origin `app` (decline when still ringing). */
    @JvmStatic fun end(context: Context, conversationId: String) {
        perform(context) { it.end(conversationId, NativeActionOrigin.APP) }
    }

    private fun perform(context: Context, action: suspend (ConversationEngine) -> Unit) {
        val application = context.applicationContext
        scope.launch {
            try { action(ConversationEngine.get(application)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* The native engine owns failure and terminal state. */ }
        }
    }
}
