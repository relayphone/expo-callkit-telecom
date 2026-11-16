package expo.modules.callkittelecom.conversations

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeCloseTraceTest {
    @Test fun cleanupDurationsShareTheCloseRequestAndSettlementFollowsTheDeferred() = runBlocking {
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val clock = AtomicLong(9_007_199_254_740_992L)
        val trace = NativeCloseTrace("call", { clock.incrementAndGet() }, signals::add)
        val released = CompletableDeferred<Unit>()
        trace.requested()
        released.invokeOnCompletion { error -> assertTrue(released.isCompleted); trace.settled(error) }
        withContext(trace) {
            traceNativeClose(NativeCloseStep.SESSION) {
                withContext(Dispatchers.Default) {
                    for (step in NativeCloseStep.entries.filter { it != NativeCloseStep.SESSION }) {
                        traceNativeClose(step) { clock.addAndGet(10) }
                    }
                }
            }
        }
        assertFalse(signals.any { it.stage == NativeMediaStage.RELEASE_COMPLETED })
        released.complete(Unit)
        assertEquals(NativeMediaStage.CLOSE_REQUESTED, signals.first().stage)
        assertEquals(NativeMediaStage.RELEASE_COMPLETED, signals.last().stage)
        assertEquals(1, signals.map { it.measurements.getValue("operationId") }.distinct().size)
        assertTrue(signals.all { it.serverCallId == "call" })
        assertTrue(signals.zipWithNext().all { (a, b) -> a.elapsedRealtimeNanos < b.elapsedRealtimeNanos })
        for (signal in signals) {
            assertEquals(signal.elapsedRealtimeNanos - signal.measurements.getValue("startedNs"), signal.measurements["durationNs"])
        }
        assertTrue(signals.drop(1).all { it.measurements["completed"] == 1L })
        assertEquals(0L, signals.last().measurements["timeout"])
    }

    @Test fun timeoutRetainsFailedSubcallDurationAndExceptionalSettlement() = runBlocking {
        val signals = mutableListOf<NativeMediaSignal>()
        val clock = AtomicLong()
        val trace = NativeCloseTrace("call", { clock.incrementAndGet() }, signals::add)
        val released = CompletableDeferred<Unit>()
        trace.requested()
        released.invokeOnCompletion(trace::settled)
        val failure = runCatching { withContext(trace) {
            traceNativeClose(NativeCloseStep.ROOM_RELEASE) { withTimeout(10) { awaitCancellation() } }
        } }.exceptionOrNull()!!
        released.completeExceptionally(failure)
        val observed = runCatching { released.await() }.exceptionOrNull()!!
        assertEquals(failure.javaClass, observed.javaClass)
        assertEquals(failure.message, observed.message)
        assertEquals(listOf(NativeMediaStage.CLOSE_REQUESTED, NativeMediaStage.CLOSE_ROOM_RELEASED,
            NativeMediaStage.RELEASE_COMPLETED), signals.map { it.stage })
        assertTrue(signals.drop(1).all { it.measurements["completed"] == 0L && it.measurements.getValue("durationNs") > 0 })
        assertEquals(1L, signals.last().measurements["timeout"])
        assertTrue(signals.all { it.reason == null })
    }

    @Test fun untracedWorkRunsOnceAndFailuresPropagateUnchanged() = runBlocking {
        var calls = 0
        assertNull(traceNativeClose(NativeCloseStep.AUDIO_HOOKS) { calls++; null })
        val expected = IllegalStateException("private SDK details")
        val actual = runCatching { traceNativeClose(NativeCloseStep.ROOM_RELEASE) { throw expected } }.exceptionOrNull()
        assertEquals(1, calls)
        assertSame(expected, actual)
    }
}
