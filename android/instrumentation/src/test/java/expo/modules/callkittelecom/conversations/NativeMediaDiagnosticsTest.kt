package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeMediaDiagnosticsTest {
    @Test fun mediaFailuresUseNamedStagesAndAllowlistedReasonsOnly() {
        for (stage in NativeAnswerStage.entries) {
            for (reason in NativeAnswerFailureReason.entries) {
                val details = NativeAnswerFailure(stage, true, 5000, reason)
                assertEquals(mapOf("kind" to "media", "stage" to stage.value, "timedOut" to true,
                    "timeoutMs" to 5000L, "reason" to reason.value),
                    conversationEnd("now", "failed", EndedFailure.MEDIA, details)["failure"])
                assertEquals(mapOf("kind" to "server"),
                    conversationEnd("now", "failed", EndedFailure.SERVER, details)["failure"])
                assertNull(conversationEnd("now", "remoteEnded", EndedFailure.MEDIA, details)["failure"])
            }
        }
        assertEquals(mapOf("kind" to "media", "stage" to "prepare", "timedOut" to false),
            conversationEnd("now", "failed", EndedFailure.MEDIA,
                NativeAnswerFailure(NativeAnswerStage.PREPARE, false))["failure"])
        assertEquals(mapOf("kind" to "media"), conversationEnd("now", "failed", EndedFailure.MEDIA)["failure"])
        assertNull(NativeAnswerFailureReason.from("token=private"))
    }

    @Test fun nestedDeadlinesRecordOnlyTheExpiredLimitAcrossDeferredRecovery() = runBlocking {
        for (expireOuter in listOf(false, true)) {
            val deadlines = NativeMediaDeadlines()
            val failure = withContext(deadlines) {
                runCatching { coroutineScope {
                    async(Dispatchers.Default) {
                        withNativeMediaDeadline(if (expireOuter) 20 else 1000) {
                            withNativeMediaDeadline(if (expireOuter) 1000 else 20) { awaitCancellation() }
                        }
                    }.await()
                } }.exceptionOrNull()
            }
            assertTrue(failure is TimeoutCancellationException)
            assertEquals(20L, deadlines.timeoutMs(checkNotNull(failure)))
        }
    }

    @Test fun callerCancellationIsNeverReportedAsAnOwnedDeadline() = runBlocking {
        val deadlines = NativeMediaDeadlines()
        val failure = withContext(deadlines) {
            runCatching { withTimeout(20) { withNativeMediaDeadline(1000) { awaitCancellation() } } }.exceptionOrNull()
        }
        assertTrue(failure is TimeoutCancellationException)
        assertNull(deadlines.timeoutMs(checkNotNull(failure)))
    }

    @Test fun deadlineBlockUsesItsOwnCancelledScopeBeforeAttemptingARecovery() = runBlocking {
        var retried = false
        val failure = runCatching {
            withNativeMediaDeadline(20) {
                try { awaitCancellation() }
                catch (error: TimeoutCancellationException) {
                    ensureActive()
                    retried = true
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertFalse(retried)
    }
}
