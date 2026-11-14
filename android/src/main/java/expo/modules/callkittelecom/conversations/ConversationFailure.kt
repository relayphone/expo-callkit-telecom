package expo.modules.callkittelecom.conversations

/** Serializable rejection details; never carries credentials or transport error messages. */
class ConversationFailure(val code: String, val nativeCode: Int? = null, message: String = code) : RuntimeException(message)
