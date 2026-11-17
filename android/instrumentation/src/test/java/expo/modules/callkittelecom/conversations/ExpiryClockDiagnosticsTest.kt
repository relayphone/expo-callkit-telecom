package expo.modules.callkittelecom.conversations

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExpiryClockDiagnosticsTest {
    @Test fun earlyExpiryStillFailsAndItsMessageRetainsBothClockOriginsAndSamples() {
        val endedAt = 14_999L
        val expiry = 15_000L
        val message = expiryClockDiagnostics(endedAt, expiry,
            ExpiryClockSample(10_000, 100), ExpiryClockSample(endedAt, 5_100))
        val error = assertThrows(AssertionError::class.java) { assertTrue(message, endedAt >= expiry) }
        val report = JSONObject(error.message!!)
        assertEquals(endedAt, report.getLong("endedAt"))
        assertEquals(expiry, report.getLong("expiresAtMillis"))
        assertEquals(-1L, report.getLong("endedMinusExpiryMs"))
        assertEquals("System.currentTimeMillis", report.getString("endedAtClock"))
        assertEquals("System.currentTimeMillis", report.getString("expiresAtClock"))
        assertEquals("SystemClock.elapsedRealtime", report.getString("elapsedClock"))
        assertTrue(report.getString("expiryOrigin").contains("test push: System.currentTimeMillis() + 5000"))
        assertEquals(10_000L, report.getJSONObject("intake").getLong("wallMillis"))
        assertEquals(100L, report.getJSONObject("intake").getLong("elapsedMillis"))
        assertEquals(endedAt, report.getJSONObject("end").getLong("wallMillis"))
        assertEquals(5_100L, report.getJSONObject("end").getLong("elapsedMillis"))
        assertEquals(4_999L, report.getLong("wallDeltaMs"))
        assertEquals(5_000L, report.getLong("elapsedDeltaMs"))
        assertEquals(-1L, report.getLong("wallMinusElapsedDeltaMs"))
    }

    @Test fun aForwardWallClockStepKeepsItsSignAndThePositiveExpiryDifference() {
        val report = JSONObject(expiryClockDiagnostics(16_000, 15_000,
            ExpiryClockSample(10_000, 100), ExpiryClockSample(16_000, 5_100)))
        assertEquals(1_000L, report.getLong("endedMinusExpiryMs"))
        assertEquals(1_000L, report.getLong("wallMinusElapsedDeltaMs"))
    }

    @Test fun anIntakeFailureDoesNotInventAnEndTimeOrClockDifference() {
        val report = JSONObject(expiryClockDiagnostics(null, 15_000, ExpiryClockSample(10_000, 100), null))
        assertTrue(report.isNull("endedAt"))
        assertTrue(report.isNull("endedMinusExpiryMs"))
        assertTrue(report.isNull("end"))
        assertFalse(report.has("wallDeltaMs"))
        assertFalse(report.has("elapsedDeltaMs"))
    }
}
