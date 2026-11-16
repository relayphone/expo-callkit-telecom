package expo.modules.callkittelecom.conversations

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaQualificationTraceTest {
    private fun consumer(): JSONObject = runBlocking {
        var now = 10L
        val trace = OutputConsumerTrace { now }
        trace.begin(); trace.captureStarted()
        trace.measure(OutputConsumerTrace.Stage.HTTP_ACK) { now = 800_000_010L }
        trace.enqueued(52321, 28320, now, now + 1)
        assertThrows(IllegalStateException::class.java) { trace.checkDrain(52321, 28320) }
        trace.snapshot()
    }

    private fun receiver() = JSONObject().put("clock", "android.elapsedRealtimeNanos").put("budgetMs", 10000)
        .put("startedNs", "9007199254740993").put("failureObservedNs", "9007199254740994")
        .put("reads", 2).put("lastRunning", false).put("signals", JSONArray().put(JSONObject()
            .put("stage", "microphonePublished").put("atNs", "9007199254740993")))

    @Test fun forcedFailureKeepsOriginalFirstAndRetainsActualGuardAndReceiverTimes() {
        val original = AssertionError("forced qualification failure <&]]> \" å")
        val error = mediaQualificationAssertion(original) { mediaQualificationTrace(consumer(), receiver()) }
        assertTrue(error.message!!.startsWith(original.toString()))
        assertSame(original, error.cause)
        val snapshot = error.message!!.substringAfter("\n")
        assertTrue(snapshot.contains("\"pendingFrames\":24001"))
        assertTrue(snapshot.contains("\"limitFrames\":24000"))
        assertTrue(snapshot.contains("HTTP_ACK"))
        assertTrue(snapshot.contains("800000010"))
        assertTrue(snapshot.contains("microphonePublished"))
        assertTrue(snapshot.contains("9007199254740993"))
        assertTrue(snapshot.toByteArray(Charsets.UTF_8).size <= MEDIA_XML_TRACE_BYTES)
        if (System.getenv("MEDIA_XML_FORCE_FAILURE") == "1") throw error
    }

    @Test fun oversizedFailureIsBoundedWithAnExplicitMarker() {
        val device = consumer()
        device.put("stages", JSONArray().apply {
            repeat(128) { put(JSONObject().put("stage", "RTC_STATS").put("enteredNs", "9007199254740993")
                .put("exitedNs", "9007199254740994").put("completed", true)) }
        })
        val original = AssertionError("forced oversized qualification failure")
        val error = mediaQualificationAssertion(original) { mediaQualificationTrace(device, receiver()) }
        val snapshot = error.message!!.substringAfter("\n")
        assertEquals(MEDIA_XML_TRACE_BYTES, snapshot.toByteArray(Charsets.UTF_8).size)
        assertTrue(snapshot.endsWith(MEDIA_XML_TRACE_TRUNCATED))
        assertTrue(snapshot.contains("\"pendingFrames\":24001"))
        assertTrue(snapshot.contains("microphonePublished"))
        if (System.getenv("MEDIA_XML_FORCE_FAILURE") == "1") throw error
    }

    @Test fun publicationFailureIsRecordedWithoutChangingTheFailureOrLeakingItsMessage() {
        val directory = Files.createTempDirectory("media-xml-publication").toFile()
        val publications = MediaEvidencePublications()
        try {
            val target = directory.resolve("result.json").apply { mkdir() }
            target.resolve("retained").writeText("previous")
            val error = assertThrows(java.io.IOException::class.java) {
                publications.write(MediaEvidencePublications.File.RESULTS) { writeMediaEvidence(target, "{}") }
            }
            val trace = mediaQualificationTrace(publications = publications)
            assertTrue(trace.contains("\"RESULTS\":\"IO_ERROR\""))
            assertTrue(trace.contains("\"CAPTURE\":\"NOT_ATTEMPTED\""))
            assertFalse(trace.contains(directory.path))
            assertEquals("previous", target.resolve("retained").readText())
            val reported = mediaQualificationAssertion(error) { trace }
            assertSame(error, reported.cause)
            publications.write(MediaEvidencePublications.File.CAPTURE) { writeMediaEvidence(directory.resolve("capture.json"), "{}") }
            assertTrue(mediaQualificationTrace(publications = publications).contains("\"CAPTURE\":\"PUBLISHED\""))
        } finally { directory.deleteRecursively() }
    }

    @Test fun arbitraryTextAndWrongClocksCannotEnterTheNumericSnapshot() {
        val unsafe = consumer().put("pcm16", "secret PCM").put("credentials", "secret credentials")
        unsafe.getJSONObject("guard").put("pendingFrames", "secret value")
        unsafe.put("stages", JSONArray().put(JSONObject().put("stage", "secret stage").put("enteredNs", 1)))
        val receiver = receiver().put("notification", "secret notification")
        receiver.getJSONArray("signals").put(JSONObject().put("stage", "microphonePublished").put("atNs", "secret time"))
        val trace = mediaQualificationTrace(unsafe, receiver, JSONObject().put("clock", "secret clock"))
        assertFalse(trace.contains("secret"))
        assertTrue(trace.contains("fixture.trace=unobserved"))
        assertTrue(trace.all { it == '\n' || it.code in 32..126 })
    }

    @Test fun fixtureScoreAndAcknowledgementRemainVisibleBeforeOversizedHistory() {
        val device = consumer().put("stages", JSONArray().apply {
            repeat(128) { put(JSONObject().put("stage", "WAIT").put("enteredNs", "10").put("exitedNs", "20").put("completed", true)) }
        })
        val fixture = JSONObject().put("clock", "host.monotonic_ns").put("observedAtNs", "1000")
            .put("frames", 48000).put("stages", JSONArray()
                .put(JSONObject().put("stage", "SCORE").put("queuedNs", "10").put("enteredNs", "100")
                    .put("exitedNs", JSONObject.NULL).put("completed", false))
                .put(JSONObject().put("stage", "HTTP_RESPONSE").put("enteredNs", "20").put("exitedNs", "30").put("completed", true)))
        val trace = mediaQualificationTrace(device, receiver(), fixture)
        assertTrue(trace.contains("host.monotonic_ns"))
        assertTrue(trace.contains("\"stage\":\"SCORE\""))
        assertTrue(trace.contains("\"queuedNs\":\"10\""))
        assertTrue(trace.contains("\"exitedNs\":null"))
        assertTrue(trace.contains("\"stage\":\"HTTP_RESPONSE\""))
        assertTrue(trace.endsWith(MEDIA_XML_TRACE_TRUNCATED))
    }

    @Test fun diagnosticFailureCannotReplaceTheOriginalAssertion() {
        val original = AssertionError("original")
        val error = mediaQualificationAssertion(original) { throw IllegalStateException("secret diagnostic") }
        assertSame(original, error.cause)
        assertEquals("$original\n[mediaQualificationTrace unavailable]", error.message)
    }

    private fun claim(clients: Int) = JSONObject().put("clock", "android.elapsedRealtimeNanos")
        .put("claimDelayMillis", 2800).put("clients", clients).put("clientObservationNs", "9007199254740993")
        .put("creations", 2).put("grant", true).put("muteBeforeAnswer", false)
        .put("signals", JSONArray().put(JSONObject().put("stage", "prejoined").put("atNs", "9007199254740992")
            .put("measurements", JSONObject().put("localMicrophones", 0).put("localPublications", 0))))

    @Test fun forcedClaimPrejoinFailureRetainsZeroClientsWithoutAnAnswer() {
        val original = assertThrows(AssertionError::class.java) { assertEquals(1, 0) }
        val error = mediaQualificationAssertion(original) { mediaQualificationTrace(claim = claim(0).put("creations", 1)) }
        assertTrue(error.message!!.startsWith(original.toString()))
        assertSame(original, error.cause)
        assertTrue(error.message!!.contains("\"clients\":0"))
        assertTrue(error.message!!.contains("\"clientObservationNs\":\"9007199254740993\""))
        assertTrue(error.message!!.contains("prejoined"))
        assertTrue(error.message!!.contains("\"localMicrophones\":0"))
        assertTrue(error.message!!.contains("\"localPublications\":0"))
        assertFalse(error.message!!.contains("microphonePublished"))
        if (System.getenv("MEDIA_XML_FORCE_FAILURE") == "1") throw error
    }

    @Test fun forcedClaimOfferFailureRetainsOnePublicationAndTwoOffers() {
        val trace = claim(1).put("signaling", JSONObject().put("negotiations", JSONArray().put(JSONObject()
            .put("clock", "python.time.monotonic_ns").put("connection", 2).put("audioPublications", 1)
            .put("audioPublisherOffers", 2).put("publisherOffers", 3).put("publisherIceRestarts", 0)
            .put("decodeErrors", 0).put("truncatedEvents", 0).put("events", JSONArray().apply {
                repeat(2) { put(JSONObject().put("atNs", "9007199254740994").put("direction", 0).put("kind", 1)
                    .put("audioSections", 1).put("negotiationId", it + 2).put("iceChanged", false)) }
            }))))
        val original = assertThrows(AssertionError::class.java) { assertEquals(1, 2) }
        val error = mediaQualificationAssertion(original) { mediaQualificationTrace(claim = trace) }
        assertTrue(error.message!!.startsWith(original.toString()))
        assertSame(original, error.cause)
        assertTrue(error.message!!.contains("claim.clock=android.elapsedRealtimeNanos"))
        assertTrue(error.message!!.contains("claim.signaling.clock=python.time.monotonic_ns"))
        assertTrue(error.message!!.contains("\"audioPublications\":1"))
        assertTrue(error.message!!.contains("\"audioPublisherOffers\":2"))
        assertTrue(error.message!!.contains("\"negotiationId\":3"))
        assertTrue(error.message!!.contains("\"iceChanged\":false"))
        trace.put("signals", JSONArray().apply { repeat(129) { put(JSONObject().put("stage", "prejoined").put("atNs", it)) } })
        val clipped = mediaQualificationTrace(claim = trace)
        assertTrue(clipped.endsWith(MEDIA_XML_TRACE_TRUNCATED))
        assertTrue(clipped.contains("\"audioPublisherOffers\":2"))
        assertTrue(clipped.toByteArray(Charsets.UTF_8).size <= MEDIA_XML_TRACE_BYTES)
        if (System.getenv("MEDIA_XML_FORCE_FAILURE") == "1") throw error
    }

    @Test fun claimDiagnosticsExcludeUntrustedTextAndWrongClocks() {
        val trace = claim(0).put("clientObservationNs", "secret time").put("token", "secret token")
        trace.getJSONArray("signals").put(JSONObject().put("stage", "secret stage").put("atNs", 1))
        trace.getJSONArray("signals").getJSONObject(0).getJSONObject("measurements").put("localMicrophones", "secret value")
        trace.put("signaling", JSONObject().put("negotiations", JSONArray().put(JSONObject()
            .put("clock", "secret clock").put("audioPublications", 999))))
        val snapshot = mediaQualificationTrace(claim = trace)
        assertTrue(snapshot.contains("\"clients\":0"))
        assertFalse(snapshot.contains("secret"))
        assertFalse(snapshot.contains("999"))
        assertTrue(snapshot.all { it == '\n' || it.code in 32..126 })
        trace.put("clock", "secret clock")
        assertFalse(mediaQualificationTrace(claim = trace).contains("\"clients\""))
    }

    @Test fun boundedClaimFailureRetainsRetirementMeasurementsAndOnlyTypedAnswerReasons() {
        val trace = claim(1)
        val signals = trace.getJSONArray("signals")
        signals.put(JSONObject().put("stage", "closeRequested").put("atNs", "9007199254740993"))
        signals.put(JSONObject().put("stage", "closeRoomReleased").put("atNs", "9007202254740993")
            .put("measurements", JSONObject().put("operationId", 7).put("startedNs", "9007199254740993")
                .put("durationNs", 3000000000L).put("completed", 0)))
        signals.put(JSONObject().put("stage", "releaseCompleted").put("atNs", "9007202254740994")
            .put("measurements", JSONObject().put("completed", 0).put("timeout", 1)))
        signals.put(JSONObject().put("stage", "answerFailed").put("atNs", "9007202254740995")
            .put("reason", "PREJOIN_CLOSE_FAILED").put("message", "secret exception")
            .put("measurements", JSONObject().put("phase", 1).put("timeout", 0)))
        signals.put(JSONObject().put("stage", "answerFailed").put("reason", "PUBLISH_PERMISSION_NOT_GRANTED"))
        signals.put(JSONObject().put("stage", "answerFailed").put("reason", "secret error"))
        repeat(100) { signals.put(JSONObject().put("stage", "decoderFirstPcm").put("atNs", it)) }
        val snapshot = mediaQualificationTrace(claim = trace)
        assertTrue(snapshot.endsWith(MEDIA_XML_TRACE_TRUNCATED))
        assertTrue(snapshot.contains("PREJOIN_CLOSE_FAILED"))
        assertTrue(snapshot.contains("PUBLISH_PERMISSION_NOT_GRANTED"))
        assertTrue(snapshot.contains("\"phase\":1"))
        assertTrue(snapshot.contains("\"timeout\":0"))
        assertTrue(snapshot.contains("\"timeout\":1"))
        assertTrue(snapshot.contains("\"durationNs\":3000000000"))
        assertTrue(snapshot.contains("\"startedNs\":\"9007199254740993\""))
        assertTrue(snapshot.contains("\"completed\":0"))
        assertFalse(snapshot.contains("secret"))
        assertTrue(snapshot.toByteArray(Charsets.UTF_8).size <= MEDIA_XML_TRACE_BYTES)
    }
}
