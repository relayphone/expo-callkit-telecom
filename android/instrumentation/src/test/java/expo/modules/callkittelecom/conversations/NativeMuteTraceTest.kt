package expo.modules.callkittelecom.conversations

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class NativeMuteTraceTest {
    @Test fun nestedSessionWorkKeepsItsOperationAndMonotonicTimesAcrossDispatchers() = runBlocking {
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val time = AtomicLong(9_007_199_254_740_992L)
        val trace = NativeMuteTrace("call", { time.incrementAndGet() }, signals::add)
        val result = withContext(trace) {
            traceNativeMute(NativeMuteStep.SERIAL) { Unit }
            traceNativeMute(NativeMuteStep.SESSION) {
                withContext(Dispatchers.Default) {
                    traceNativeMute(NativeMuteStep.PUBLICATION) { Unit }
                    traceNativeMute(NativeMuteStep.BOUNDARY) { null }
                }
            }
        }
        assertNull(result)
        assertEquals(listOf(NativeMediaStage.MUTE_SERIAL_ENTERED, NativeMediaStage.MUTE_SERIAL_EXITED,
            NativeMediaStage.MUTE_SESSION_ENTERED, NativeMediaStage.MUTE_PUBLICATION_ENTERED,
            NativeMediaStage.MUTE_PUBLICATION_EXITED, NativeMediaStage.MUTE_BOUNDARY_ENTERED,
            NativeMediaStage.MUTE_BOUNDARY_EXITED, NativeMediaStage.MUTE_SESSION_EXITED), signals.map { it.stage })
        assertEquals(1, signals.map { it.measurements.getValue("operationId") }.distinct().size)
        assertTrue(signals.all { it.serverCallId == "call" })
        assertTrue(signals.zipWithNext().all { (a, b) -> a.elapsedRealtimeNanos < b.elapsedRealtimeNanos })
        assertTrue(signals.filter { it.stage.name.endsWith("EXITED") }.all { it.measurements["completed"] == 1L })
    }

    @Test fun cancellationWhileAcquiringLockHasAnExitWithoutEnteringSession() = runBlocking {
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val time = AtomicLong()
        val lock = Mutex(locked = true)
        val entered = CompletableDeferred<Unit>()
        val trace = NativeMuteTrace("call", { time.incrementAndGet() }) {
            signals += it
            if (it.stage == NativeMediaStage.MUTE_SERIAL_ENTERED) entered.complete(Unit)
        }
        val work = launch(trace) {
            traceNativeMute(NativeMuteStep.SERIAL) { lock.lock() }
            try { traceNativeMute(NativeMuteStep.SESSION) { error("must not enter") } }
            finally { lock.unlock() }
        }
        entered.await(); work.cancelAndJoin()
        assertEquals(listOf(NativeMediaStage.MUTE_SERIAL_ENTERED, NativeMediaStage.MUTE_SERIAL_EXITED), signals.map { it.stage })
        assertEquals(0L, signals.last().measurements["completed"])
        assertTrue(lock.isLocked)
        lock.unlock()
    }

    @Test fun sessionFailureIsPreservedAndEveryEnteredBoundaryExits() = runBlocking {
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val time = AtomicLong()
        val failure = IllegalStateException("failed publication")
        val trace = NativeMuteTrace("call", { time.incrementAndGet() }, signals::add)
        val result = runCatching { withContext(trace) {
            traceNativeMute(NativeMuteStep.SESSION) {
                traceNativeMute(NativeMuteStep.PUBLICATION) { throw failure }
            }
        } }
        assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals(listOf(NativeMediaStage.MUTE_SESSION_ENTERED, NativeMediaStage.MUTE_PUBLICATION_ENTERED,
            NativeMediaStage.MUTE_PUBLICATION_EXITED, NativeMediaStage.MUTE_SESSION_EXITED), signals.map { it.stage })
        assertTrue(signals.takeLast(2).all { it.measurements["completed"] == 0L })
        assertFalse(signals.any { it.stage == NativeMediaStage.MUTE_BOUNDARY_ENTERED })
    }

    @Test fun untracedNullableWorkRunsOnce() = runBlocking {
        var calls = 0
        assertNull(traceNativeMute(NativeMuteStep.PUBLICATION) { calls++; null })
        assertEquals(1, calls)
    }
}
