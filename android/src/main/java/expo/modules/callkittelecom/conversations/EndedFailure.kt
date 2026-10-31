package expo.modules.callkittelecom.conversations

/**
 * Why a conversation failed, when Android knows it: the TypeScript `EndedFailureKind` that a
 * failed conversation's `ended.failure` carries. Android reports only these kinds; iOS reports more.
 */
internal enum class EndedFailure(val kind: String) {
    /** No handler placed or joined the call: none was set, it rejected, or it missed its deadline. */
    NOT_PLACED("notPlaced"),
    /** The native media session couldn't be prepared, started or kept running. */
    MEDIA("media"),
    /** An answered call wasn't reported connected before the answer deadline. */
    CONNECT_TIMED_OUT("connectTimedOut"),
    /** The app or its server reported the failure. */
    SERVER("server");

    companion object {
        private val handlerCodes = setOf("deadline_exceeded", "handler_failed")

        /** `NOT_PLACED` when a start or join failed because its handler rejected it or nobody settled it in time. */
        fun ofHandler(error: Throwable): EndedFailure? =
            if (error is ConversationFailure && error.code in handlerCodes) NOT_PLACED else null
    }
}

/** The TypeScript `ConversationEnd`. Only a failed conversation carries its cause, when Android knows it. */
internal fun conversationEnd(at: String, reason: String, failure: EndedFailure?): Map<String, Any?> =
    if (reason == "failed" && failure != null) {
        mapOf("at" to at, "reason" to reason, "failure" to mapOf("kind" to failure.kind))
    } else {
        mapOf("at" to at, "reason" to reason)
    }
