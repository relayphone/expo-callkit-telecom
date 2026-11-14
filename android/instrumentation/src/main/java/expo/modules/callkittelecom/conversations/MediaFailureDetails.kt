package expo.modules.callkittelecom.conversations

/** Sanitized fixture failure details; request URLs, tokens and arbitrary SDK messages stay private. */
internal data class MediaFailureDetail(val type: String, val message: String)

internal fun mediaFailureDetails(error: Throwable): List<MediaFailureDetail> =
    generateSequence(error) { it.cause }.take(8).map { cause ->
        val message = cause.message.orEmpty()
        val safe = message == "Output capture consumer fell behind" ||
            message == "Media fixture request failed" || Regex("Timed out waiting for \\d+ ms").matches(message)
        MediaFailureDetail(cause.javaClass.simpleName, if (safe) message else "Details retained in test XML")
    }.toList()
