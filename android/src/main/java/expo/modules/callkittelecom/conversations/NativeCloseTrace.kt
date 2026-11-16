package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext

internal enum class NativeCloseStep(val stage: NativeMediaStage) {
    LIFETIME_JOIN(NativeMediaStage.CLOSE_LIFETIME_JOINED),
    SESSION(NativeMediaStage.CLOSE_SESSION_COMPLETED),
    OBSERVATION_DRAIN(NativeMediaStage.CLOSE_OBSERVATION_DRAINED),
    DISCONNECT_EMISSION(NativeMediaStage.CLOSE_DISCONNECT_EMITTED),
    TRACKS_AND_SINKS(NativeMediaStage.CLOSE_TRACKS_REMOVED),
    AUDIO_HOOKS(NativeMediaStage.CLOSE_AUDIO_HOOKS_CLOSED),
    ROOM_RELEASE(NativeMediaStage.CLOSE_ROOM_RELEASED)
}

/** Cleanup observations share one close request across dispatcher switches and cancellation. */
internal class NativeCloseTrace(private val serverId: String,
    private val now: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val emit: (NativeMediaSignal) -> Unit = {
        NativeMediaSignals.emit(it.serverCallId, it.stage, it.measurements, it.elapsedRealtimeNanos)
    }) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeCloseTrace> {
        private val sequence = AtomicLong()
    }
    private val operationId = sequence.incrementAndGet()
    private val requestedAt = now()

    fun requested() = signal(NativeMediaStage.CLOSE_REQUESTED, requestedAt)

    suspend fun <T> measure(step: NativeCloseStep, work: suspend () -> T): T {
        val startedAt = now()
        var completed = false
        try { return work().also { completed = true } }
        finally { signal(step.stage, startedAt, completed) }
    }

    /** Called by the controller deferred's completion handler, including exceptional settlement. */
    fun settled(error: Throwable?) = signal(NativeMediaStage.RELEASE_COMPLETED, requestedAt,
        error == null, error is TimeoutCancellationException)

    private fun signal(stage: NativeMediaStage, startedAt: Long, completed: Boolean? = null, timeout: Boolean? = null) {
        val observedAt = now()
        val values = mapOf("operationId" to operationId, "startedNs" to startedAt, "durationNs" to observedAt - startedAt) +
            (completed?.let { mapOf("completed" to if (it) 1L else 0L) } ?: emptyMap()) +
            (timeout?.let { mapOf("timeout" to if (it) 1L else 0L) } ?: emptyMap())
        emit(NativeMediaSignal(serverId, stage, observedAt, values))
    }
}

internal suspend fun <T> traceNativeClose(step: NativeCloseStep, work: suspend () -> T): T {
    val trace = currentCoroutineContext()[NativeCloseTrace]
    return if (trace == null) work() else trace.measure(step, work)
}
