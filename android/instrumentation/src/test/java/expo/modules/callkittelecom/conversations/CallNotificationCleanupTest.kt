package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CallNotificationCleanupTest {
    private val notification = CallNotificationState("host|6999|null|uid", 6999, null, "incoming", 66, true, false)

    @Test fun waitsForObservedRemovalRatherThanTelecomCompletion() = runBlocking {
        var active = listOf(notification)
        var time = 0L
        val cleanup = CallNotificationCleanup({ active }, { ++time })
        assertEquals(listOf(notification), cleanup.sample("telecom-empty"))
        var polls = 0
        cleanup.awaitEmpty {
            assertEquals(listOf(notification), cleanup.samples.last().notifications)
            polls++
            active = emptyList()
        }
        assertEquals(1, polls)
        assertEquals(listOf("telecom-empty", "awaiting-removal", "awaiting-removal"), cleanup.samples.map { it.phase })
        assertEquals(notification, cleanup.samples.first().notifications.single())
        assertTrue(cleanup.samples.last().notifications.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), cleanup.samples.map { it.elapsedNanos })
    }

    @Test fun aPersistentNotificationFailsTheCallerDeadlineAndRemainsInEvidence() = runBlocking {
        val cleanup = CallNotificationCleanup({ listOf(notification) })
        val result = runCatching { withTimeout(10) { cleanup.awaitEmpty { awaitCancellation() } } }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(listOf(notification), cleanup.sample("failure"))
        assertEquals(notification, cleanup.samples.last().notifications.single())
    }

    @Test fun cancellationIsFatalAndAnAlreadyEmptyStateNeedsNoPause() = runBlocking {
        val cleanup = CallNotificationCleanup({ emptyList() })
        cleanup.awaitEmpty { error("No fixed delay after removal") }
        val failure = CancellationException("observer cancelled")
        val retained = CallNotificationCleanup({ listOf(notification) })
        assertSame(failure, runCatching { retained.awaitEmpty { throw failure } }.exceptionOrNull())
    }
}
