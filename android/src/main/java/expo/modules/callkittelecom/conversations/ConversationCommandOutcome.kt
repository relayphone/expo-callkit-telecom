package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Stable command code shared by native outcomes and JavaScript rejections. */
@JvmInline
value class ConversationCommandCode private constructor(val value: String) {
    companion object {
        val DEADLINE_EXCEEDED = ConversationCommandCode("deadline_exceeded")
        val HANDLER_FAILED = ConversationCommandCode("handler_failed")
        val UNKNOWN_CONVERSATION = ConversationCommandCode("unknown_conversation")
        val INVALID_ARGUMENTS = ConversationCommandCode("invalid_arguments")
        val UNSUPPORTED = ConversationCommandCode("unsupported")
        val CONVERSATION = ConversationCommandCode("conversation")
        internal fun from(value: String) = ConversationCommandCode(value)
    }
}

/** The public conversation phase reached when a native command settles. */
enum class ConversationCommandStatus(val value: String) {
    PREVIEW("preview"), RINGING("ringing"), DIALING("dialing"), CONNECTING("connecting"),
    CONNECTED("connected"), RECONNECTING("reconnecting"), HELD("held"), ENDED("ended");
}

/** The conversation's logical end, independent of remaining platform cleanup. */
enum class ConversationEndReason(val value: String) {
    LOCAL("local"), REMOTE_ENDED("remoteEnded"), FAILED("failed"), UNANSWERED("unanswered"),
    ANSWERED_ELSEWHERE("answeredElsewhere"), DECLINED_ELSEWHERE("declinedElsewhere");
}

/** Settlement of an app-origin native command. Refusal reasons retain the handler's exact text. */
sealed interface ConversationCommandOutcome {
    /** State has been published. Answer can reach CONNECTING before media connects. */
    data class Performed(val conversationId: String, val status: ConversationCommandStatus,
                         val endReason: ConversationEndReason? = null) : ConversationCommandOutcome
    data class Refused(val reason: String) : ConversationCommandOutcome
    /** [message] and [code] use the same normalization as the JavaScript command bridge. */
    data class Failed(val code: ConversationCommandCode, val message: String,
                      val nativeCode: Int? = null) : ConversationCommandOutcome
}

internal fun performedCommand(id: String, state: Map<String, Any?>): ConversationCommandOutcome.Performed {
    val status = ConversationCommandStatus.entries.single { it.value == state["status"] }
    val reason = (state["ended"] as? Map<*, *>)?.get("reason")
    return ConversationCommandOutcome.Performed(id, status,
        if (reason == null) null else ConversationEndReason.entries.single { it.value == reason })
}

internal const val CONVERSATION_COMMAND_OUTCOME_TIMEOUT_MILLIS = 70_000L

/** Bound only the waiter; [Deferred] remains owned by the process command scope. */
internal suspend fun Deferred<ConversationCommandOutcome>.awaitCommandOutcome(
    timeoutMillis: Long = CONVERSATION_COMMAND_OUTCOME_TIMEOUT_MILLIS,
): ConversationCommandOutcome = withTimeoutOrNull(timeoutMillis) { await() }
    ?: CommandRejection.deadlineExceeded().outcome
