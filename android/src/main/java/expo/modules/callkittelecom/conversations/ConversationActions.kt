package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*

/** A person's native call action. Outgoing start continues to use the JavaScript placement handler. */
sealed interface ConversationAction {
    val conversationId: String
    val serverCallId: String?
    /** `system` or `app`. System hold and mute are observations of state already applied by the OS. */
    val origin: String
    data class Answer(override val conversationId: String, override val serverCallId: String?, override val origin: String) : ConversationAction
    data class Decline(override val conversationId: String, override val serverCallId: String?, override val origin: String) : ConversationAction
    data class End(override val conversationId: String, override val serverCallId: String?, override val origin: String) : ConversationAction
    data class Hold(override val conversationId: String, override val serverCallId: String?, override val origin: String, val held: Boolean) : ConversationAction
    data class Mute(override val conversationId: String, override val serverCallId: String?, override val origin: String, val muted: Boolean) : ConversationAction
}

sealed interface ConversationActionResult {
    /** Fulfil the action. Answer uses push-shaped [media] only when no prewarmed session exists. */
    data class Proceed(val media: Map<String, Any?>? = null) : ConversationActionResult
    /**
     * Refuse an answer, or an app-origin hold/mute. Answer also ends the call with [endReason].
     * Decline/end always disconnect. System hold/mute retain the OS state; refusal is only signalled.
     * Use a short [reason] code without names, numbers, credentials or server error messages.
     */
    data class Refuse(val reason: String, val endReason: String = "failed") : ConversationActionResult
}

/** Suspend for server agreement; honour cancellation and perform blocking work off the main dispatcher. */
fun interface ConversationActionHandler { suspend fun handle(action: ConversationAction): ConversationActionResult }

/** Configure during Application.onCreate, before accepting calls. Decisions are captured per native receipt. */
object ConversationActions {
    @Volatile internal var handler: ConversationActionHandler? = null
        private set
    /** Install a native handler. `null` restores the existing native behavior. */
    @JvmStatic fun configure(handler: ConversationActionHandler?) { this.handler = handler }
}

// Leave 1.5 s of Core-Telecom's 5 s callback budget for settlement.
internal const val CONVERSATION_ACTION_BUDGET_MILLIS = 3_500L
internal const val CONVERSATION_END_BUDGET_MILLIS = 3_000L

/** The worker cannot delay settlement by ignoring cancellation; its late result is discarded. */
internal class ConversationActionDecision(
    private val scope: CoroutineScope,
    private val signal: (NativeMediaStage, String?) -> Unit,
    private val budgetMillis: Long = CONVERSATION_ACTION_BUDGET_MILLIS,
    // Reserve 0.5 s to request the disconnect within the 3 s local-end ceiling.
    private val endBudgetMillis: Long = CONVERSATION_END_BUDGET_MILLIS - 500L,
) {
    suspend fun ask(action: ConversationAction, handler: ConversationActionHandler): ConversationActionResult {
        signal(NativeMediaStage.HANDLER_STARTED, null)
        val work = scope.async(Dispatchers.IO) {
            try { handler.handle(action) }
            catch (_: TimeoutCancellationException) { ConversationActionResult.Refuse("handlerTimedOut") }
            catch (_: Exception) { ConversationActionResult.Refuse("handlerFailed") }
        }
        val budget = if (action is ConversationAction.End || action is ConversationAction.Decline) endBudgetMillis else budgetMillis
        val result = try {
            withTimeoutOrNull(budget) { work.await() } ?: ConversationActionResult.Refuse("handlerTimedOut")
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            ConversationActionResult.Refuse("handlerFailed")
        } finally { work.cancel() }
        signal(NativeMediaStage.HANDLER_RESPONDED, when (result) {
            is ConversationActionResult.Proceed -> "proceed"
            is ConversationActionResult.Refuse -> result.reason
        })
        return result
    }
}

internal fun ConversationActionResult.Refuse.rejection(): ConversationFailure =
    ConversationFailure(if (reason == "handlerTimedOut") "deadline_exceeded" else "handler_failed")

internal fun ConversationAction.canRefuse(invocationOrigin: String = origin): Boolean = this is ConversationAction.Answer ||
    (invocationOrigin == "app" && (this is ConversationAction.Hold || this is ConversationAction.Mute))

internal fun ConversationActionResult.Refuse.validEndReason(): String = endReason.takeIf {
    it in setOf("local", "remoteEnded", "failed", "unanswered", "answeredElsewhere", "declinedElsewhere")
} ?: "failed"
