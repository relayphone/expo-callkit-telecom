package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Primary system control, including hold/resume and mute/unmute. */
enum class SystemCommandAction { ANSWER, DECLINE, END, HOLD, MUTE }

/** Where the system control entered the SDK. */
enum class SystemCommandSource { NOTIFICATION, TELECOM }

/** Settlement of a system-origin command, using the same outcomes as app controls. */
data class SystemCommandOutcome(
    val conversationId: String,
    val action: SystemCommandAction,
    val source: SystemCommandSource,
    val outcome: ConversationCommandOutcome,
)

internal const val SYSTEM_COMMAND_OUTCOME_BUFFER_CAPACITY = 64

/** Observation never blocks system controls on an absent or slow app collector. */
internal class SystemCommandOutcomes {
    private val events = MutableSharedFlow<SystemCommandOutcome>(replay = 0,
        extraBufferCapacity = SYSTEM_COMMAND_OUTCOME_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow = events.asSharedFlow()

    suspend fun observe(id: String, action: SystemCommandAction, source: SystemCommandSource,
                        accepted: Deferred<ConversationCommandOutcome>,
                        timeoutMillis: Long = CONVERSATION_COMMAND_OUTCOME_TIMEOUT_MILLIS): ConversationCommandOutcome {
        val outcome = accepted.awaitCommandOutcome(timeoutMillis)
        events.tryEmit(SystemCommandOutcome(id, action, source, outcome))
        return outcome
    }
}
