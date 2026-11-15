package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OutputConsumerTraceTest {
    @Test fun exactDrainLimitRemainsAcceptedAndEnqueueTimestampsAreMonotonic() {
        var now = 10L
        val trace = OutputConsumerTrace { now }
        trace.begin(); trace.captureStarted()
        trace.enqueued(12000, 0, 20, 21)
        trace.enqueued(24000, 0, 30, 32)
        trace.checkDrain(24000, 0)
        now = 40
        val before = trace.snapshot()
        assertEquals(24000, before.getInt("pendingFrames"))
        assertEquals("10", before.getString("captureStartedNs"))
        assertEquals("20", before.getJSONObject("enqueue").getString("firstNs"))
        assertEquals("10", before.getJSONObject("enqueue").getString("largestGapNs"))
        assertTrue(before.isNull("guard"))
        trace.drained(24000, 24000); now = 40
        assertEquals(0, trace.snapshot().getInt("pendingFrames"))
    }

    @Test fun oneFrameBeyondTheOriginalLimitStillFailsWithTheActualBacklog() {
        val trace = OutputConsumerTrace { 900_000_001L }
        val error = runCatching { trace.checkDrain(52321, 28320) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Output capture consumer fell behind", error!!.message)
        val guard = trace.snapshot().getJSONObject("guard")
        assertEquals(52321, guard.getInt("capturedFrames"))
        assertEquals(28320, guard.getInt("drainedFrames"))
        assertEquals(24001, guard.getInt("pendingFrames"))
        assertEquals(24000, guard.getInt("limitFrames"))
        assertEquals("900000001", guard.getString("atNs"))
    }

    @Test fun aBlockedConsumerStageIsRetainedWithoutBlockingTheProducerOrGuard() = runBlocking {
        var now = 100L
        val trace = OutputConsumerTrace { now }
        val gate = CompletableDeferred<Unit>()
        val http = async(start = CoroutineStart.UNDISPATCHED) {
            trace.measure(OutputConsumerTrace.Stage.HTTP_ACK) { gate.await(); "acknowledged" }
        }
        try {
            now = 800_000_100L
            trace.enqueued(24001, 0, 799_000_000L, 799_000_001L)
            assertTrue(runCatching { trace.checkDrain(24001, 0) }.exceptionOrNull() is IllegalStateException)
            val active = trace.snapshot().getJSONArray("stages").getJSONObject(0)
            assertEquals("HTTP_ACK", active.getString("stage"))
            assertEquals("100", active.getString("enteredNs")); assertTrue(active.isNull("exitedNs"))
            assertFalse(active.getBoolean("completed"))
            gate.complete(Unit)
            assertEquals("acknowledged", http.await())
            val done = trace.snapshot().getJSONArray("stages").getJSONObject(0)
            assertEquals("800000100", done.getString("exitedNs")); assertTrue(done.getBoolean("completed"))
        } finally { gate.complete(Unit); http.cancelAndJoin() }
    }

    @Test fun failureAndCancellationKeepTheirIdentityAndNeverEnterTheTraceAsText() = runBlocking {
        for (error in listOf(IllegalStateException("private request detail"), CancellationException("private cancellation"))) {
            var now = 10L
            val trace = OutputConsumerTrace { now }
            val actual = runCatching { trace.measure(OutputConsumerTrace.Stage.RTC_STATS) { now = 20; throw error } }.exceptionOrNull()
            assertSame(error, actual)
            val stage = trace.snapshot().getJSONArray("stages").getJSONObject(0)
            assertEquals("20", stage.getString("exitedNs")); assertFalse(stage.getBoolean("completed"))
            assertFalse(trace.snapshot().toString().contains("private"))
        }
    }

    @Test fun stageHistoryIsBoundedAndABeginClearsOldCaptureEvidence() = runBlocking {
        var now = 0L
        val trace = OutputConsumerTrace { now++ }
        repeat(130) { trace.measure(OutputConsumerTrace.Stage.DRAIN) { it } }
        val saved = trace.snapshot()
        assertEquals(128, saved.getJSONArray("stages").length()); assertEquals(2, saved.getInt("droppedSpans"))
        trace.begin()
        val cleared = trace.snapshot()
        assertEquals(0, cleared.getJSONArray("stages").length()); assertEquals(0, cleared.getInt("droppedSpans"))
        assertTrue(cleared.isNull("guard")); assertTrue(cleared.isNull("captureStartedNs"))
    }
}
