package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private data class PresentationObserved<T>(val value: T)

/** One observation budget for an asynchronous presentation predicate. */
internal suspend fun <T> awaitPresentationObservation(
    predicate: String,
    read: suspend () -> T,
    accepts: (T) -> Boolean,
    describe: (T) -> String,
    timeoutMillis: Long = 5000,
    pause: suspend () -> Unit = { delay(10) }
): T {
    var last = "not sampled"
    val observed = withTimeoutOrNull(timeoutMillis) {
        var value = read()
        var accepted = accepts(value)
        last = describe(value)
        while (!accepted) {
            pause(); value = read()
            accepted = accepts(value)
            last = describe(value)
        }
        PresentationObserved(value)
    }
    if (observed == null) throw AssertionError("Timed out waiting for $timeoutMillis ms: predicate=$predicate, last=$last")
    return observed.value
}

/** Always clean up the fixture after its assertions. */
internal suspend fun <T> withPresentationCleanup(body: suspend () -> T, cleanup: suspend () -> Unit): T {
    var primary: Throwable? = null
    try { return body() }
    catch (error: Throwable) { primary = error; throw error }
    finally {
        try { withContext(NonCancellable) { cleanup() } }
        catch (error: Throwable) {
            if (primary == null) throw error
            if (error !== primary) primary.addSuppressed(error)
        }
    }
}
