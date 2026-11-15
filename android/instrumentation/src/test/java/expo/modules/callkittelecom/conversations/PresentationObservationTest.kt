package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PresentationObservationTest {
    private data class Cards(val ids: List<Int>, val foreground: Boolean)

    @Test fun waitsForTheObservedPredicateWithoutAnExtraDelayAfterSuccess() = runBlocking {
        var sample = Cards(listOf(7, 9), false)
        var pauses = 0
        val result = awaitPresentationObservation("answered card uses the primary ID", { sample },
            { it == Cards(listOf(7), true) }, { "ids=${it.ids}, foreground=${it.foreground}" },
            pause = { pauses++; sample = Cards(listOf(7), true) })
        assertEquals(Cards(listOf(7), true), result)
        assertEquals(1, pauses)
    }

    @Test fun timeoutNamesThePredicateAndItsLastActualSampleWithoutResampling() = runBlocking {
        var reads = 0
        val error = runCatching { awaitPresentationObservation("waiting card count=2", {
            reads++; Cards(listOf(7), false)
        }, { it.ids.size == 2 }, { "ids=${it.ids}, foreground=${it.foreground}" },
            timeoutMillis = 20, pause = { awaitCancellation() }) }.exceptionOrNull()
        assertTrue(error is AssertionError)
        assertTrue(error!!.message!!.contains("waiting card count=2"))
        assertTrue(error.message!!.contains("ids=[7], foreground=false"))
        assertTrue(error.message!!.contains("20 ms"))
        assertEquals(1, reads)
    }

    @Test fun anUnfinishedFirstReadRetainsTheNamedPredicate() = runBlocking {
        val error = runCatching { awaitPresentationObservation("initial Telecom drain", { awaitCancellation() },
            { true }, { "must not describe a missing sample" }, timeoutMillis = 20) }.exceptionOrNull()
        assertTrue(error is AssertionError)
        assertTrue(error!!.message!!.contains("initial Telecom drain"))
        assertTrue(error.message!!.contains("not sampled"))
    }

    @Test fun cancellationAndANestedReaderTimeoutAreNotRelabeled() = runBlocking {
        val cancelled = CancellationException("cancelled observation")
        val cancellation = runCatching { awaitPresentationObservation("card", { throw cancelled },
            { true }, { "unavailable" }) }.exceptionOrNull()
        assertTrue(generateSequence(cancellation) { it.cause }.any { it === cancelled })
        val error = runCatching { awaitPresentationObservation("card", {
            withTimeout(10) { awaitCancellation() }
        }, { true }, { "unavailable" }) }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertTrue(error!!.message!!.contains("10 ms"))
    }

    @Test fun cleanupCannotReplaceThePresentationAssertion() = runBlocking {
        val primary = AssertionError("Answer used the wrong notification ID")
        val cleanup = IllegalStateException("notification still posted")
        val error = runCatching { withPresentationCleanup({ throw primary }, { throw cleanup }) }.exceptionOrNull()
        assertSame(primary, error)
        assertTrue(error!!.suppressed.any { failure -> generateSequence(failure) { it.cause }.any { it === cleanup } })
    }

    @Test fun aCleanupOnlyFailureRemainsFatal() = runBlocking {
        val cleanup = IllegalStateException("Telecom still occupied")
        val error = runCatching { withPresentationCleanup({ Unit }, { throw cleanup }) }.exceptionOrNull()
        assertTrue(generateSequence(error) { it.cause }.any { it === cleanup })
    }

    @Test fun cleanupStillRunsInAnActiveContextAfterParentCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var activeAtCleanup = false
        val work = launch {
            withPresentationCleanup({ started.complete(Unit); awaitCancellation() }, {
                activeAtCleanup = currentCoroutineContext().isActive
                yield()
            })
        }
        started.await(); work.cancelAndJoin()
        assertTrue(activeAtCleanup)
        assertTrue(work.isCancelled)
    }
}
