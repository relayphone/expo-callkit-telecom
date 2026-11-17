package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationUiRetryTest {
    @Test fun shadeReopensAtMostOnceAndNeverAfterTheExistingBudget() {
        var now = 0L; var reopened = 0
        val retry = NotificationUiRetry { now }
        now = 1499; retry.onceAfter(1500) { reopened++ }; assertEquals(0, reopened)
        now = 1500; retry.onceAfter(1500) { reopened++ }
        now = 3000; retry.onceAfter(1500) { reopened++ }; assertEquals(1, reopened)
        val expired = NotificationUiRetry { now }
        now += 5000; expired.onceAfter(1500) { reopened++ }; assertEquals(1, reopened)
    }
    @Test fun rejectedHomePressFallsBackToTheIntentWithoutRetryingTheBody() = runTest {
        val calls = mutableListOf<String>(); var launcher = false
        NotificationUiRetry { testScheduler.currentTime }.home({ calls += "press"; false }, { launcher },
            { calls += "intent"; launcher = true })
        assertEquals(listOf("press", "intent"), calls)
    }
    @Test fun acceptedHomeWaitsForTheLauncherBeforeOneFallback() = runTest {
        val calls = mutableListOf<Long>(); var launcher = false
        NotificationUiRetry { testScheduler.currentTime }.home({ true }, { launcher },
            { calls += testScheduler.currentTime; launcher = true })
        assertEquals(listOf(1000L), calls)
    }
    @Test fun missingLauncherStillTimesOutWithOnlyOneIntent() = runTest {
        var intents = 0
        val failure = runCatching { withTimeout(5000) {
            NotificationUiRetry { testScheduler.currentTime }.home({ true }, { false }, { intents++ })
        } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException); assertEquals(1, intents)
        assertEquals(5000L, testScheduler.currentTime)
    }
}
