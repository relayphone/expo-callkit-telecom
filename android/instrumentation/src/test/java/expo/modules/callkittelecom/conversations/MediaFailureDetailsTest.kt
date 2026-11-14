package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaFailureDetailsTest {
    @Test fun retainsNestedDrainCauseWithoutPublishingArbitraryMessages() {
        val error = AssertionError("private token or URL", IllegalStateException("Output capture consumer fell behind"))
        assertEquals(listOf(MediaFailureDetail("AssertionError", "Details retained in test XML"),
            MediaFailureDetail("IllegalStateException", "Output capture consumer fell behind")), mediaFailureDetails(error))
    }

    @Test fun retainsTheActualTimeoutBudget() = runBlocking {
        val error = runCatching { withTimeout(10) { awaitCancellation() } }.exceptionOrNull()!!
        assertEquals(MediaFailureDetail("TimeoutCancellationException", "Timed out waiting for 10 ms"),
            mediaFailureDetails(error).first())
    }
}
