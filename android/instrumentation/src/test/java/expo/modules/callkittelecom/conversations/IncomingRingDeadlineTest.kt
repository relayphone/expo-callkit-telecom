package expo.modules.callkittelecom.conversations

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IncomingRingDeadlineTest {
    @Test fun explicitExpiryReplacesTheDefaultInEitherDirectionAndPastExpiryIsImmediate() = runBlocking {
        val waits = mutableListOf<Long>()
        val deadline = IncomingRingDeadline(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)) { waits += it }
        for (expiry in listOf(null, 6_000L, 100_000L, 1_000L, 999L, Long.MIN_VALUE)) deadline.await(expiry, 45_000)
        assertEquals(listOf(45_000L, 5_000L, 99_000L, 0L, 0L, 0L), waits)
        deadline.await(null, 12_000)
        assertEquals(12_000L, waits.last())
    }

    @Test fun admissionTimeSpentBeforeReportingDoesNotExtendExpiry() = runBlocking {
        var now = 1_000L
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock { require(zone == ZoneOffset.UTC); return this }
            override fun instant(): Instant = Instant.ofEpochMilli(now)
            override fun millis(): Long = now
        }
        var wait = -1L
        val deadline = IncomingRingDeadline(clock) { wait = it }
        now = 1_600L
        deadline.await(2_000, 45_000)
        assertEquals(400L, wait)
    }

    @Test fun farFutureExpiryCannotWrapIntoAnImmediateTimeout() = runBlocking {
        var wait = -1L
        val deadline = IncomingRingDeadline(Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC)) { wait = it }
        deadline.await(Long.MAX_VALUE, 45_000)
        assertEquals(Long.MAX_VALUE, wait)
    }

    @Test fun answeringOrEndingCanCancelTheOwnedWait() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val deadline = IncomingRingDeadline(waitMillis = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        val timeout = launch { deadline.await(null, 45_000); fail("cancelled wait fired") }
        entered.await()
        timeout.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }
}
