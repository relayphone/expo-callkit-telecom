package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeReceiverWarmupTest {
    @Test fun slowConnectKeepsTheConnectedRoomAndReportsSkippedWarmup() = runTest {
        val signals = mutableListOf<Pair<NativeMediaStage, Map<String, Long>>>()
        var prepared = false
        withNativeMediaDeadline(8000, testScheduler.timeSource) {
            delay(6500) // Connected and identity-checked, with less than two seconds left.
            warmNativeReceiver({}, {}, {}, {}, { awaitCancellation() }) { stage, values -> signals += stage to values }
            prepared = true
        }
        assertTrue(prepared)
        assertEquals(7750, testScheduler.currentTime)
        assertEquals("prewarmReceiverWarmupSkipped", signals.single().first.value)
        assertEquals(mapOf("remainingMs" to 250L, "warmupMs" to 1250L,
            "remoteTrackReady" to 1L, "subscriberReady" to 1L, "playbackStarted" to 1L,
            "mutedOutput" to 1L, "decoded" to 0L), signals.single().second)
    }

    @Test fun connectOverThePreparationDeadlineStillFails() = runTest {
        val deadlines = NativeMediaDeadlines()
        var warmupReached = false
        val failure = withContext(deadlines) {
            runCatching { withNativeMediaDeadline(8000, testScheduler.timeSource) {
                delay(8001)
                warmupReached = true
                warmNativeReceiver({}, {}, {}, {}, {}) { _, _ -> fail("No optional timeout") }
            } }.exceptionOrNull()
        }
        assertFalse(warmupReached)
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(8000L, deadlines.timeoutMs(checkNotNull(failure)))
        assertEquals("TimeoutCancellationException", failure.javaClass.simpleName)
    }

    @Test fun fastConnectCompletesEveryWarmupStepWithoutSkipping() = runTest {
        val completed = mutableListOf<String>()
        fun step(name: String): suspend () -> Unit = { delay(100); completed += name }
        withNativeMediaDeadline(8000, testScheduler.timeSource) {
            delay(100)
            warmNativeReceiver(step("track"), step("subscriber"), step("playback"), step("muted"), step("decoded")) {
                _, _ -> fail("Receiver warmup completed")
            }
        }
        assertEquals(listOf("track", "subscriber", "playback", "muted", "decoded"), completed)
        assertEquals(600, testScheduler.currentTime)
    }

    @Test fun noWarmupStartsInsideTheCompletionMargin() = runTest {
        var skipped: Map<String, Long>? = null
        val unexpected: suspend () -> Unit = { fail("No warmup budget remains") }
        withNativeMediaDeadline(8000, testScheduler.timeSource) {
            delay(7900)
            warmNativeReceiver(unexpected, unexpected, unexpected, unexpected, unexpected) { _, values -> skipped = values }
        }
        assertEquals(7900, testScheduler.currentTime)
        assertEquals(0L, skipped?.get("warmupMs"))
        assertEquals(100L, skipped?.get("remainingMs"))
        assertTrue(skipped!!.filterKeys { it != "warmupMs" && it != "remainingMs" }.values.all { it == 0L })
    }

    @Test fun unpublishedRemoteStillStopsAtTwoSecondsWhenBudgetIsAmple() = runTest {
        var skipped: Map<String, Long>? = null
        withNativeMediaDeadline(8000, testScheduler.timeSource) {
            delay(100)
            warmNativeReceiver({ awaitCancellation() }, {}, {}, {}, {}) { _, values -> skipped = values }
        }
        assertEquals(2100, testScheduler.currentTime)
        assertEquals(2000L, skipped?.get("warmupMs"))
        assertEquals(5900L, skipped?.get("remainingMs"))
    }

    @Test fun enclosingAnswerDeadlineAlsoBoundsOptionalWork() = runTest {
        var skipped: Map<String, Long>? = null
        withNativeMediaDeadline(5000, testScheduler.timeSource) {
            withNativeMediaDeadline(8000, testScheduler.timeSource) {
                delay(4500)
                warmNativeReceiver({ awaitCancellation() }, {}, {}, {}, {}) { _, values -> skipped = values }
            }
        }
        assertEquals(4750, testScheduler.currentTime)
        assertEquals(250L, skipped?.get("warmupMs"))
        assertEquals(250L, skipped?.get("remainingMs"))
    }

    @Test fun callerCancellationStillFailsPreparationWithoutASkipSignal() = runTest {
        val entered = CompletableDeferred<Unit>()
        var prepared = false
        val preparation = launch {
            withNativeMediaDeadline(8000, testScheduler.timeSource) {
                warmNativeReceiver({ entered.complete(Unit); awaitCancellation() }, {}, {}, {}, {}) {
                    _, _ -> fail("Caller cancellation is not an optional timeout")
                }
                prepared = true
            }
        }
        entered.await()
        preparation.cancelAndJoin()
        assertTrue(preparation.isCancelled)
        assertFalse(prepared)
    }
}
