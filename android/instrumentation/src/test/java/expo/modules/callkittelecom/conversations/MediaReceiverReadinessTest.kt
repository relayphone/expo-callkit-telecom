package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaReceiverReadinessTest {
    private fun pending() = JSONObject().put("running", false).put("frames", 0).put("markers", 0)
    private fun ready() = JSONObject().put("running", true).put("generation", 7)
        .put("muted", true).put("profile", "tones-v2").put("error", JSONObject.NULL)

    @Test fun waitsForBothDevicePrejoinAndTheCapturedPendingReceiver() = runBlocking {
        var joined = false
        var reads = 0
        var pauses = 0
        var prejoinChecks = 0
        val subscribed = ready()
        val actual = awaitMediaReceiver({ joined }, {
            assertTrue(joined)
            assertEquals(1, prejoinChecks)
            if (reads++ == 0) pending() else subscribed
        }, onPrejoined = { prejoinChecks++ }, pause = { joined = true; pauses++ })
        assertSame(subscribed, actual)
        assertEquals(2, reads)
        assertEquals(2, pauses)
    }

    @Test fun subscriptionReadinessDoesNotWaitAwayAnUnmutedPublication() = runBlocking {
        val subscribed = ready().put("muted", false)
        assertSame(subscribed, awaitMediaReceiver({ true }, { subscribed }, pause = { fail("must not pause") }))
        assertFalse(subscribed.getBoolean("muted"))
        var reads = 0
        var pauses = 0
        val afterPending = awaitMediaReceiver({ true }, {
            if (reads++ == 0) pending() else subscribed
        }, pause = { pauses++ })
        assertSame(subscribed, afterPending)
        assertFalse(afterPending.getBoolean("muted"))
        assertEquals(2, reads)
        assertEquals(1, pauses)
    }

    @Test fun aPrejoinMuteFailureStopsBeforeReceiverPolling() = runBlocking {
        var reads = 0
        val error = runCatching { awaitMediaReceiver({ true }, { reads++; pending() },
            onPrejoined = { throw AssertionError("publication is not muted") }) }.exceptionOrNull()
        assertTrue(error is AssertionError)
        assertEquals(0, reads)
    }

    @Test fun aPendingReceiverCannotOutliveThePrejoinTimeout() = runBlocking {
        var reads = 0
        val error = runCatching { awaitMediaReceiver({ true }, { reads++; pending() },
            timeoutMillis = 20, pause = { awaitCancellation() }) }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(1, reads)
    }

    @Test fun prejoinAndReceiverReadsUseOneTimeoutScope() = runBlocking {
        var prejoinScope: Job? = null
        var readerScope: Job? = null
        var joined = false
        awaitMediaReceiver({ joined }, {
            readerScope = currentCoroutineContext()[Job]
            ready()
        }, pause = {
            prejoinScope = currentCoroutineContext()[Job]
            joined = true
        })
        assertNotNull(prejoinScope)
        assertSame(prejoinScope, readerScope)
    }

    @Test fun stoppedMalformedAndFailedReceiversStayFatal() = runBlocking {
        for (peer in listOf(pending().put("generation", 7), pending().put("error", "StreamFailure"),
            pending().put("frames", 1), pending().put("markers", 1), JSONObject(),
            pending().put("running", "false"))) {
            var reads = 0
            var pauses = 0
            val error = runCatching { awaitMediaReceiver({ true }, { reads++; peer },
                pause = { pauses++; fail("must not poll a stopped or malformed receiver") }) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(1, reads)
            assertEquals(0, pauses)
        }
        val failure = IllegalStateException("fixture read failed")
        val actual = runCatching { awaitMediaReceiver({ true }, { throw failure }) }.exceptionOrNull()
        assertTrue(generateSequence(actual) { it.cause }.any { it === failure })
    }
}
