package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

/** Sanitized command details encoded in the message that Expo delivers to JavaScript. */
internal class CommandRejection private constructor(
    val code: String,
    private val message: String,
    private val nativeCode: Int? = null,
) {
    val encodedMessage: String
        get() {
            val details = JSONObject().put("code", code).put("message", message)
            nativeCode?.let {
                details.put("nativeDomain", "androidx.core.telecom").put("nativeCode", it)
            }
            return "expo-conversation:$details"
        }

    companion object {
        fun from(error: Exception): CommandRejection = when (error) {
            is TimeoutCancellationException -> CommandRejection("deadline_exceeded", "Conversation deadline exceeded")
            is CancellationException -> throw error
            is ConversationFailure -> CommandRejection(error.code,
                if (error.code == "refused") error.message.orEmpty() else "Conversation operation failed", error.nativeCode)
            else -> CommandRejection(
                error.message?.takeIf { it in setOf("invalid_arguments", "unknown_conversation", "unsupported") }
                    ?: "conversation",
                "Conversation operation failed",
            )
        }
    }
}
