package expo.modules.callkittelecom.conversations

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaReceiverTraceTest {
    private fun pending() = JSONObject().put("running", false).put("frames", 0).put("markers", 0)
    private fun ready() = JSONObject().put("running", true).put("generation", 7)
        .put("frames", 480).put("markers", 0).put("error", JSONObject.NULL)
    private fun http(block: suspend CoroutineScope.(MockWebServer, OkHttpClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        server.start()
        try { block(server, client) }
        finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test fun realHttpPollsRetainTimesPhasesAndOnlySafeReceiverFields() = http { server, client ->
        server.enqueue(MockResponse().setBody(pending().toString()))
        server.enqueue(MockResponse().setBody(ready().put("markerId", "private marker")
            .put("pcm16", "private PCM").put("token", "private credential").toString()))
        var now = 100L
        val trace = MediaReceiverTrace { now++ }
        val peer = awaitMediaReceiver({ true }, { phase -> readMediaFixture(client,
            Request.Builder().url(server.url("/private-path")).header("Authorization", "Bearer private-auth").build(), phase) },
            pause = {}, trace = trace)
        assertEquals(7L, peer.getLong("generation"))
        val snapshot = trace.snapshot()
        assertEquals(10_000, snapshot.getInt("budgetMs"))
        assertEquals(2, snapshot.getInt("reads"))
        assertTrue(snapshot.has("completedNs"))
        assertFalse(snapshot.has("failureObservedNs"))
        assertEquals(480, snapshot.getJSONObject("last").getInt("frames"))
        val polls = snapshot.getJSONArray("polls")
        repeat(2) { index ->
            val poll = polls.getJSONObject(index)
            assertEquals("PARSED", poll.getString("phase"))
            assertTrue(poll.getString("endedNs").toLong() > poll.getString("startedNs").toLong())
            assertTrue(poll.isNull("errorClass"))
        }
        assertFalse(snapshot.toString().contains("private"))
        assertFalse(mediaQualificationTrace(receiver = snapshot).contains("private"))
    }

    @Test fun aMissingHeaderStillTimesOutWithTheOriginalCancellationAndSafeLastState() = http { server, client ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val trace = MediaReceiverTrace(System::nanoTime)
        val error = runCatching { awaitMediaReceiver({ true }, { phase -> readMediaFixture(client,
            Request.Builder().url(server.url("/")).build(), phase) }, timeoutMillis = 200, trace = trace) }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        val snapshot = trace.snapshot()
        assertEquals(200, snapshot.getInt("budgetMs"))
        assertEquals(1, snapshot.getInt("reads"))
        assertTrue(snapshot.has("failureObservedNs"))
        val poll = snapshot.getJSONArray("polls").getJSONObject(0)
        assertEquals("ENQUEUED", poll.getString("phase"))
        assertEquals("TimeoutCancellationException", poll.getString("errorClass"))
        assertFalse(poll.isNull("endedNs"))
        assertEquals(0, snapshot.getJSONObject("last").length())
    }

    @Test fun cancellationOfAStalledBodyKeepsTheReachedPhaseAndFreezesThePoll() = http { server, client ->
        server.enqueue(MockResponse().setBody("{").setHeader("Content-Length", "999"))
        val body = CompletableDeferred<Unit>()
        val trace = MediaReceiverTrace(System::nanoTime)
        val read = launch {
            awaitMediaReceiver({ true }, { phase -> readMediaFixture(client,
                Request.Builder().url(server.url("/")).build()) {
                phase(it)
                if (it == MediaReceiverHttpPhase.BODY) body.complete(Unit)
            } }, trace = trace)
        }
        withTimeout(2000) { body.await() }
        read.cancelAndJoin()
        val poll = trace.snapshot().getJSONArray("polls").getJSONObject(0)
        assertEquals("BODY", poll.getString("phase"))
        assertFalse(poll.isNull("endedNs"))
        assertTrue(poll.getString("errorClass").endsWith("CancellationException"))

        var late: ((MediaReceiverHttpPhase) -> Unit)? = null
        val original = java.io.IOException("private exception message")
        val actual = runCatching { trace.read { phase -> late = phase; throw original } }.exceptionOrNull()
        assertSame(original, actual)
        val before = trace.snapshot().toString()
        late!!(MediaReceiverHttpPhase.PARSED)
        assertEquals(before, trace.snapshot().toString())
        assertFalse(before.contains("private"))
    }

    @Test fun httpAndParseFailuresKeepTheirPhaseAndClassWithoutTheirBody() = http { server, client ->
        for ((response, phase, errorClass) in listOf(
            Triple(MockResponse().setResponseCode(503).setBody("private service body"), "HEADERS", "IllegalStateException"),
            Triple(MockResponse().setBody("private invalid JSON"), "BODY", "JSONException"))) {
            server.enqueue(response)
            val trace = MediaReceiverTrace(System::nanoTime)
            val error = runCatching { awaitMediaReceiver({ true }, { update -> readMediaFixture(client,
                Request.Builder().url(server.url("/")).build(), update) }, trace = trace) }.exceptionOrNull()
            assertEquals(errorClass, error!!.javaClass.simpleName)
            val snapshot = trace.snapshot()
            val poll = snapshot.getJSONArray("polls").getJSONObject(0)
            assertEquals(phase, poll.getString("phase"))
            assertEquals(errorClass, poll.getString("errorClass"))
            assertFalse(snapshot.toString().contains("private"))
        }
    }

    @Test fun boundedHistoryKeepsTheLastGenerationAndRejectsPayloadAndMalformedFields() = runBlocking {
        var now = 9007199254740993L
        val trace = MediaReceiverTrace { now++ }
        repeat(40) { trace.read { phase -> phase(MediaReceiverHttpPhase.PARSED); ready() } }
        val snapshot = trace.snapshot()
        assertEquals(40, snapshot.getInt("reads"))
        assertEquals(8, snapshot.getInt("droppedPolls"))
        assertEquals(32, snapshot.getJSONArray("polls").length())
        assertEquals(7, snapshot.getJSONObject("last").getInt("generation"))
        val safe = safeReceiver(JSONObject().put("running", "private boolean").put("generation", "private token")
            .put("frames", -1).put("markers", 0.5).put("error", "private exception message").put("rtc", "private payload"))
        assertEquals(1, safe.length())
        assertEquals("UnclassifiedError", safe.getString("errorClass"))
        assertEquals("RuntimeError", safeReceiver(ready().put("error", "RuntimeError")).getString("errorClass"))
        val original = AssertionError("original receiver assertion")
        val reported = mediaQualificationAssertion(original) { mediaQualificationTrace(receiver = snapshot) }
        assertSame(original, reported.cause)
        assertTrue(reported.message!!.contains("receiver.latestPoll="))
        assertTrue(reported.message!!.contains("\"generation\":7"))
        assertTrue(reported.message!!.contains(MEDIA_XML_TRACE_TRUNCATED))
    }
}
