package expo.modules.callkittelecom.conversations

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** The receive-only preparation boundary that failed. */
enum class NativePrewarmStage(val value: String) {
    CONNECT("connect"), IDENTITY("identity"), SUBSCRIBE("subscribe"), DEADLINE("deadline")
}

/** A bounded diagnostic: the exception type is retained, never its message. */
data class NativePrewarmFailure(val stage: NativePrewarmStage, val errorType: String)

internal enum class NativeAnswerStage(val value: String, val phase: Long) {
    PREPARE("prepare", 1), AUDIO_SESSION("audioSession", 2), ACTIVATE("activate", 3)
}

/** Allowlisted native Answer reasons and their app-facing names. */
enum class NativeAnswerFailureReason(val nativeValue: String, val value: String) {
    PUBLISH_PERMISSION_NOT_GRANTED("PUBLISH_PERMISSION_NOT_GRANTED", "publishPermissionNotGranted"),
    PREJOIN_CLOSE_FAILED("PREJOIN_CLOSE_FAILED", "prejoinCloseFailed");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.nativeValue == value }
    }
}

internal data class NativeAnswerFailure(val stage: NativeAnswerStage, val timedOut: Boolean,
    val timeoutMs: Long? = null, val reason: NativeAnswerFailureReason? = null) {
    fun snapshot(): Map<String, Any> = buildMap {
        put("stage", stage.value)
        put("timedOut", timedOut)
        timeoutMs?.let { put("timeoutMs", it) }
        reason?.let { put("reason", it.value) }
    }
}

internal sealed interface NativeMediaDiagnostic {
    data class Prewarm(val failure: NativePrewarmFailure) : NativeMediaDiagnostic
    data class Answer(val failure: NativeAnswerFailure) : NativeMediaDiagnostic
}

/** Records only the deadline that actually expired, retaining cancellation semantics. */
internal class NativeMediaDeadlines : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeMediaDeadlines>
    private var expired: Pair<TimeoutCancellationException, Long>? = null
    @Synchronized fun record(error: TimeoutCancellationException, timeoutMs: Long) { expired = error to timeoutMs }
    @Synchronized fun timeoutMs(error: Throwable): Long? = expired?.takeIf { recorded ->
        generateSequence(error) { it.cause }.any { observed ->
            generateSequence<Throwable>(recorded.first) { it.cause }.any { it === observed }
        }
    }?.second
}

private data class NativeDeadlineResult<T>(val value: T)

/** Optional work can reserve time for completion within the nearest enclosing deadline. */
internal class NativeMediaBudget(private val remaining: () -> Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeMediaBudget>
    fun remainingMs(): Long = remaining().coerceAtLeast(0)
}

internal suspend fun <T> withNativeMediaDeadline(timeoutMs: Long, timeSource: TimeSource = TimeSource.Monotonic,
    block: suspend CoroutineScope.() -> T): T {
    val started = timeSource.markNow()
    val parent = currentCoroutineContext()[NativeMediaBudget]
    val budget = NativeMediaBudget {
        minOf(timeoutMs - started.elapsedNow().inWholeMilliseconds, parent?.remainingMs() ?: Long.MAX_VALUE)
    }
    var cancellation: TimeoutCancellationException? = null
    val result = withTimeoutOrNull(timeoutMs) {
        try { withContext(budget) { NativeDeadlineResult(block(this)) } }
        catch (error: TimeoutCancellationException) { cancellation = error; throw error }
    }
    if (result != null) return result.value
    val error = checkNotNull(cancellation)
    currentCoroutineContext()[NativeMediaDeadlines]?.record(error, timeoutMs)
    throw error
}
