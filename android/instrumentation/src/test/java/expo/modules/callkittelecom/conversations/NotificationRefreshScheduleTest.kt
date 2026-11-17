package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.*
import org.junit.Test

class NotificationRefreshScheduleTest {
    private fun schedule() = NotificationRefreshSchedule<String>({ a, b -> a == b })

    @Test fun aFirstCardDoesNotWaitForAnotherCardsRefresh() {
        val schedule = schedule()
        schedule.posted(1, "ongoing", 0)
        schedule.request(1, "updated")
        schedule.request(2, "first ringing")
        assertEquals(1100.milliseconds, schedule.delay(1, 0))
        assertEquals(Duration.ZERO, schedule.delay(2, 0))
    }

    @Test fun manyRefreshesKeepOnlyTheLatestValueAndOneInterval() {
        val schedule = schedule()
        schedule.posted(1, "initial", 0)
        repeat(12) { schedule.request(1, "avatar-$it") }
        assertEquals("avatar-11", schedule.pending(1))
        assertEquals(800.milliseconds, schedule.delay(1, 300_000_000))
        assertEquals(Duration.ZERO, schedule.delay(1, 1_100_000_000))
        schedule.posted(1, checkNotNull(schedule.pending(1)), 1_100_000_000)
        assertNull(schedule.pending(1))
        assertFalse(schedule.request(1, "avatar-11"))
    }

    @Test fun revertingToThePostedValueDropsTheQueuedRefresh() {
        val schedule = schedule()
        schedule.posted(1, "initial", 0)
        assertTrue(schedule.request(1, "temporary"))
        assertFalse(schedule.request(1, "initial"))
        assertNull(schedule.pending(1))
    }

    @Test fun aLifecyclePostSupersedesAnOldQueuedCard() {
        val schedule = schedule()
        schedule.posted(1, "first call", 0)
        schedule.request(1, "first avatar")
        schedule.posted(1, "promoted call", 100_000_000)
        assertNull(schedule.pending(1))
        schedule.request(1, "promoted avatar")
        assertEquals(1000.milliseconds, schedule.delay(1, 200_000_000))
    }

    @Test fun removalAndServiceStopDiscardPendingContentAndTiming() {
        val schedule = schedule()
        schedule.posted(1, "first", 0)
        schedule.request(1, "updated")
        schedule.remove(1)
        assertNull(schedule.pending(1))
        assertEquals(Duration.ZERO, schedule.delay(1, 0))
        schedule.posted(2, "second", 0)
        schedule.request(2, "updated")
        schedule.clear()
        assertNull(schedule.pending(2))
    }

    @Test fun cancellationOfTwoImmediateJobsCompletesBothFinallyBlocks() = runBlocking {
        val jobs = mutableMapOf<Int, Job>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val finished = mutableSetOf<Int>()
        val queued = (1..2).map { id ->
            scope.launch(start = CoroutineStart.LAZY) {
                try { awaitCancellation() }
                finally { jobs.remove(id); finished += id }
            }.also { jobs[id] = it; it.start() }
        }
        try {
            cancelNotificationRefreshJobs(jobs)
            assertTrue(jobs.isEmpty())
            assertEquals(setOf(1, 2), finished)
            assertTrue(queued.all { it.isCancelled })
        } finally { parent.cancel() }
    }
}
