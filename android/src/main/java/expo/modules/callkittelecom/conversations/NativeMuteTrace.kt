package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

internal enum class NativeMuteStep(val entered: NativeMediaStage, val exited: NativeMediaStage) {
    SERIAL(NativeMediaStage.MUTE_SERIAL_ENTERED, NativeMediaStage.MUTE_SERIAL_EXITED),
    SESSION(NativeMediaStage.MUTE_SESSION_ENTERED, NativeMediaStage.MUTE_SESSION_EXITED),
    PUBLICATION(NativeMediaStage.MUTE_PUBLICATION_ENTERED, NativeMediaStage.MUTE_PUBLICATION_EXITED),
    BOUNDARY(NativeMediaStage.MUTE_BOUNDARY_ENTERED, NativeMediaStage.MUTE_BOUNDARY_EXITED)
}

/** One controller mute, propagated through session dispatcher switches without changing its deadline. */
internal class NativeMuteTrace(private val serverId: String,
    private val now: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val emit: (NativeMediaSignal) -> Unit = {
        NativeMediaSignals.emit(it.serverCallId, it.stage, it.measurements, it.elapsedRealtimeNanos)
    }) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeMuteTrace> {
        private val sequence = AtomicLong()
    }
    private val operationId = sequence.incrementAndGet()

    suspend fun <T> measure(step: NativeMuteStep, work: suspend () -> T): T {
        signal(step.entered)
        var completed = false
        try { return work().also { completed = true } }
        finally { signal(step.exited, if (completed) 1L else 0L) }
    }

    private fun signal(stage: NativeMediaStage, completed: Long? = null) {
        val observedAt = now()
        val values = mapOf("operationId" to operationId) + (completed?.let { mapOf("completed" to it) } ?: emptyMap())
        emit(NativeMediaSignal(serverId, stage, observedAt, values))
    }
}

internal suspend fun <T> traceNativeMute(step: NativeMuteStep, work: suspend () -> T): T {
    val trace = currentCoroutineContext()[NativeMuteTrace]
    return if (trace == null) work() else trace.measure(step, work)
}
