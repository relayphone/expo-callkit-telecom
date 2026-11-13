package expo.modules.callkittelecom.conversations

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** The notification's ringing or ongoing presentation. */
enum class CallPhase { RINGING, ONGOING }

internal const val RINGING_ALERT_INTERVAL_MILLIS = 1100L

/** Android rate-limits audible alerts per package, including alerts for different calls. */
internal class RingingAlertSchedule {
    private var lastPostMillis: Long? = null
    fun delayMillis(nowMillis: Long): Long = lastPostMillis?.let {
        (it + RINGING_ALERT_INTERVAL_MILLIS - nowMillis).coerceAtLeast(0)
    } ?: 0
    fun posted(nowMillis: Long) { lastPostMillis = nowMillis }
}

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
    /** Wording for the primary card's held-call summary; used only when calls are held. */
    val heldSummary: String? = null,
)

/** Runs on main; return immediately without I/O. The public snapshot entry contains no media grants. */
fun interface CallNotificationCustomizer {
    fun content(conversation: Map<String, Any?>, phase: CallPhase): CallNotificationContent
}

/** Customizes subsequent notifications while the library retains CallStyle, service and controls. */
object ConversationPresentation {
    @Volatile private var customizer: CallNotificationCustomizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Configure from Application.onCreate; null restores the library's content. */
    @JvmStatic fun configure(customizer: CallNotificationCustomizer?) { this.customizer = customizer }

    /**
     * Re-run app content for live calls and update existing cards in place. Safe from any thread;
     * customization runs on main. Ringing updates restart the ring and are coalesced at least
     * 1.1 seconds apart. Updates queued for a call that leaves ringing are discarded.
     * Retained ending cards and calls without a posted notification are left unchanged.
     */
    @JvmStatic fun refresh(context: Context) {
        val application = context.applicationContext
        scope.launch { ConversationService.refreshPresentation(application) }
    }

    internal fun resolve(conversation: Map<String, Any?>, phase: CallPhase,
                         defaults: CallNotificationContent): CallNotificationContent {
        val content = runCatching { customizer?.content(conversation, phase) }.getOrNull() ?: CallNotificationContent()
        return content.copy(
            title = content.title ?: defaults.title,
            avatar = content.avatar ?: defaults.avatar,
            fullScreenIntent = if (phase == CallPhase.RINGING) content.fullScreenIntent ?: defaults.fullScreenIntent else null,
            contentIntent = content.contentIntent ?: defaults.contentIntent,
            channelId = content.channelId ?: defaults.channelId,
            heldSummary = content.heldSummary ?: defaults.heldSummary,
        )
    }
}

/** Main-confined public entries admitted to notification presentation, including hold recency. */
internal class CallNotificationRoster {
    private data class Entry(val state: Map<String, Any?>, val entered: Long)
    private val entries = linkedMapOf<String, Entry>()
    private var sequence = 0L

    fun put(id: String, state: Map<String, Any?>): Boolean {
        val value = state + ("id" to id)
        val previous = entries[id]
        if (previous?.state == value) return false
        val entered = if (previous == null || previous.state["status"] != value["status"]) ++sequence else previous.entered
        entries[id] = Entry(value, entered)
        return true
    }
    fun refresh(states: List<Map<String, Any?>>): Boolean {
        var changed = false
        for (state in states) {
            val id = state["id"] as String
            if (id in entries && put(id, state)) changed = true
        }
        return changed
    }
    fun remove(id: String): Boolean = entries.remove(id) != null
    fun ending(id: String): Boolean = if (id in entries) put(id, mapOf("status" to "ending")) else false
    fun liveStates(): List<Map<String, Any?>> = entries.values.map { it.state }.filter {
        it["status"] in listOf("ringing", "dialing", "connecting", "connected", "held")
    }

    data class Plan(val primary: Map<String, Any?>?, val waiting: List<Map<String, Any?>>, val heldCount: Int) {
        val heldSummary: String? get() = when (heldCount) {
            0 -> null
            1 -> "1 call on hold"
            else -> "$heldCount calls on hold"
        }
    }
    fun plan(retainedPrimaryId: String? = null): Plan {
        val active = entries.values.filter { it.state["status"] in listOf("dialing", "connecting", "connected") }
        val held = entries.values.filter { it.state["status"] == "held" }
        val ringing = entries.values.filter { it.state["status"] == "ringing" }
        val ending = entries.values.filter { it.state["status"] == "ending" }
        val retained = ending.firstOrNull { it.state["id"] == retainedPrimaryId }
        val primary = retained ?: active.maxByOrNull { it.entered }
            ?: held.maxByOrNull { it.entered } ?: ending.firstOrNull() ?: ringing.firstOrNull()
        val secondary = ringing + ending + if (retained != null) active + held else emptyList()
        return Plan(primary?.state, secondary.filter { it !== primary }.map { it.state }, held.size)
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

    /** Publish DTMF through native LiveKit media with origin `app`. */
    @JvmStatic fun playTones(context: Context, conversationId: String, digits: String) {
        perform(context) { it.tones(conversationId, digits) }
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
