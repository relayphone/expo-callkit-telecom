package expo.modules.callkittelecom.conversations

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaClaimCaptureTest {
    private fun capture(first: Int) = JSONObject().put("clock", "android.elapsedRealtimeNanos")
        .put("columns", JSONArray().put("firstFrame")).put("overflow", false).put("rtc", JSONArray())
        .put("apmStates", JSONArray()).put("postApm", JSONObject().put("observed", false))
        .put("callbacks", JSONArray((first until first + 300).map { listOf(it) }))

    @Test fun eachFailingClaimKeepsItsOwnCaptureAcrossLaterJourneyWrites() {
        val directory = Files.createTempDirectory("claim-capture").toFile()
        val file = directory.resolve("capture-trace.json")
        try {
            writeMediaCaptureEvidence(file, capture(1))
            repeat(2) { index ->
                val failure = AssertionError("original $index")
                var reads = 0
                retainMediaClaimCapture(file, failure, "hooks-${index + 1}", 2800) { reads++; capture((index + 1) * 1000) }
                assertEquals(1, reads)
                assertTrue(failure.suppressed.isEmpty())
            }
            writeMediaCaptureEvidence(file, capture(9999))
            val saved = JSONObject(file.readText())
            assertEquals(9999, saved.getJSONArray("callbacks").getJSONArray(0).getInt(0))
            val failures = saved.getJSONArray("claimFailures")
            assertEquals(2, failures.length())
            repeat(2) { index ->
                val claim = failures.getJSONObject(index)
                assertEquals("hooks-${index + 1}", claim.getString("claimId"))
                assertEquals(2800, claim.getInt("claimDelayMillis"))
                assertEquals(256, claim.getJSONArray("callbacks").length())
                assertEquals(44, claim.getInt("droppedCallbacks"))
                assertEquals((index + 1) * 1000, claim.getJSONArray("callbacks").getJSONArray(0).getInt(0))
                assertEquals((index + 1) * 1000 + 299, claim.getJSONArray("callbacks").getJSONArray(255).getInt(0))
            }
            repeat(4) { retainMediaClaimCapture(file, AssertionError(), "hooks-${it + 3}", 0) { capture(it) } }
            val bounded = JSONObject(file.readText()).getJSONArray("claimFailures")
            assertEquals(4, bounded.length())
            assertEquals("hooks-3", bounded.getJSONObject(0).getString("claimId"))
            assertEquals("hooks-6", bounded.getJSONObject(3).getString("claimId"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun capturePublicationFailureCannotReplaceTheOriginalClaimFailure() {
        val directory = Files.createTempDirectory("claim-capture-failure").toFile()
        try {
            val file = directory.resolve("missing/capture-trace.json")
            val original = AssertionError("original claim failure")
            retainMediaClaimCapture(file, original, "hooks-123", 2800) { capture(0) }
            assertEquals(1, original.suppressed.size)
            assertTrue(original.suppressed.single() is java.io.IOException)
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}
