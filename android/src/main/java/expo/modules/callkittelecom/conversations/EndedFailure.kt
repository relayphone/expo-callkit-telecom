package expo.modules.callkittelecom.conversations

/**
 * The TypeScript `EndedFailure` carried by a failed conversation's `ended.failure`.
 * Filtered failures also carry the incoming policy cause; other kinds have no cause.
 */
internal enum class EndedFailure(val kind: String, val cause: NativeIncomingFilterReason? = null) {
    /** No handler placed or joined the call: none was set, it rejected, or it missed its deadline. */
    NOT_PLACED("notPlaced"),
    /** The native media session couldn't be prepared, started or kept running. */
    MEDIA("media"),
    /** Core-Telecom removed the session without a requested end. */
    FRAMEWORK_RESET("frameworkReset"),
    /** An answered call wasn't reported connected before the answer deadline. */
    CONNECT_TIMED_OUT("connectTimedOut"),
    /** The app or its server reported the failure. */
    SERVER("server"),
    FILTERED_DND("filtered", NativeIncomingFilterReason.DO_NOT_DISTURB),
    FILTERED_BLOCK_LIST("filtered", NativeIncomingFilterReason.BLOCK_LIST),
    FILTERED_OTHER("filtered", NativeIncomingFilterReason.OTHER);

    companion object {
        private val handlerCodes = setOf("deadline_exceeded", "handler_failed")

        /** `NOT_PLACED` when a start or join failed because its handler rejected it or nobody settled it in time. */
        fun ofHandler(error: Throwable): EndedFailure? =
            if (error is ConversationFailure && error.code in handlerCodes) NOT_PLACED else null

        fun filtered(reason: NativeIncomingFilterReason): EndedFailure = when (reason) {
            NativeIncomingFilterReason.DO_NOT_DISTURB -> FILTERED_DND
            NativeIncomingFilterReason.BLOCK_LIST -> FILTERED_BLOCK_LIST
            NativeIncomingFilterReason.OTHER -> FILTERED_OTHER
        }
    }
}

/** The TypeScript `ConversationEnd`. Only a failed conversation carries its cause, when Android knows it. */
internal fun conversationEnd(at: String, reason: String, failure: EndedFailure?): Map<String, Any?> =
    if (reason == "failed" && failure != null) {
        mapOf("at" to at, "reason" to reason, "failure" to buildMap {
            put("kind", failure.kind)
            failure.cause?.let { put("cause", it.value) }
        })
    } else {
        mapOf("at" to at, "reason" to reason)
    }
