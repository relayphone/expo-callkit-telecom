package expo.modules.callkittelecom.conversations

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnswerJourneyTraceTest {
    @Test fun endpointEmissionsRecordAvailableAndCurrentTypesFromAdmission() {
        var now = 0L
        val trace = AnswerJourneyTrace("test", { now })
        trace.endpoint(AnswerEndpointPhase.CURRENT_ENDPOINT, listOf(99))
        now = 10_000_000
        trace.mark(AnswerJourneyPhase.CORE_ADMITTED)
        now = 15_000_000
        trace.endpoint(AnswerEndpointPhase.AVAILABLE_ENDPOINTS, listOf(1, 2))
        now = 18_000_000
        trace.endpoint(AnswerEndpointPhase.CURRENT_ENDPOINT, listOf(1))
        val report = JSONObject(trace.json("example", false)).getJSONObject("endpointTrace")
        assertEquals("SystemClock.elapsedRealtimeNanos", report.getString("clock"))
        assertEquals(10.0, report.getDouble("admittedElapsedMs"), 0.0)
        val emissions = report.getJSONArray("emissions")
        assertEquals(2, emissions.length())
        assertEquals("AVAILABLE_ENDPOINTS", emissions.getJSONObject(0).getString("phase"))
        assertEquals(15.0, emissions.getJSONObject(0).getDouble("elapsedMs"), 0.0)
        assertEquals(5.0, emissions.getJSONObject(0).getDouble("sinceAdmissionMs"), 0.0)
        assertEquals("[1,2]", emissions.getJSONObject(0).getJSONArray("types").toString())
        assertEquals("CURRENT_ENDPOINT", emissions.getJSONObject(1).getString("phase"))
        assertEquals(8.0, emissions.getJSONObject(1).getDouble("sinceAdmissionMs"), 0.0)
        assertEquals("[1]", emissions.getJSONObject(1).getJSONArray("types").toString())
    }

    @Test fun endpointHistoryRetainsTheFirstEntriesAndBoundsEachTypeList() {
        var now = 0L
        val trace = AnswerJourneyTrace("test", { now })
        trace.mark(AnswerJourneyPhase.CORE_ADMITTED)
        repeat(1000) { index ->
            now += 1_000_000
            trace.endpoint(AnswerEndpointPhase.AVAILABLE_ENDPOINTS, (index until index + 10).toList())
        }
        val report = JSONObject(trace.json("example", false)).getJSONObject("endpointTrace")
        val emissions = report.getJSONArray("emissions")
        assertEquals(32, report.getInt("limit"))
        assertEquals(8, report.getInt("typeLimit"))
        assertEquals(32, emissions.length())
        assertEquals(968, report.getInt("droppedEvents"))
        assertEquals(1.0, emissions.getJSONObject(0).getDouble("elapsedMs"), 0.0)
        assertEquals(32.0, emissions.getJSONObject(31).getDouble("elapsedMs"), 0.0)
        assertEquals(31, emissions.getJSONObject(31).getJSONArray("types").getInt(0))
        assertEquals(8, emissions.getJSONObject(0).getJSONArray("types").length())
        assertEquals(10, emissions.getJSONObject(0).getInt("endpointCount"))
        assertEquals(2, emissions.getJSONObject(0).getInt("droppedTypes"))
    }

    @Test fun admissionAfterTimeoutCannotRewriteTheMissingAdmissionObservation() {
        var now = 0L
        val trace = AnswerJourneyTrace("test", { now })
        now = 5_000_000_000
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.AUDIO_ROUTE)
        // A callback at the same clock tick still arrived after the cutoff.
        trace.mark(AnswerJourneyPhase.CORE_ADMITTED)
        trace.endpoint(AnswerEndpointPhase.CURRENT_ENDPOINT, listOf(1))
        val report = JSONObject(trace.json("example", false)).getJSONObject("endpointTrace")
        assertTrue(report.isNull("admittedElapsedMs"))
        assertEquals(0, report.getJSONArray("emissions").length())
        assertEquals(5000.0, report.getDouble("stoppedElapsedMs"), 0.0)
    }

    @Test fun routeTimeoutSealsEndpointObservationsBeforeCleanupEmissions() {
        var now = 0L
        val trace = AnswerJourneyTrace("test", { now })
        trace.mark(AnswerJourneyPhase.CORE_ADMITTED)
        trace.endpoint(AnswerEndpointPhase.AVAILABLE_ENDPOINTS, listOf(1))
        now = 5_000_000_000
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.AUDIO_ROUTE)
        val before = JSONObject(trace.json("example", false)).getJSONObject("endpointTrace")
        trace.mark(AnswerJourneyPhase.CLEANUP_STARTED)
        trace.endpoint(AnswerEndpointPhase.CURRENT_ENDPOINT, listOf(2))
        now += 5_000_000_000
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.PLATFORM_REMOVED)
        val after = JSONObject(trace.json("example", false)).getJSONObject("endpointTrace")
        assertEquals(before.toString(), after.toString())
        assertEquals(5000.0, after.getDouble("stoppedElapsedMs"), 0.0)
        assertEquals(0, after.getInt("droppedEvents"))
    }

    @Test fun shadeSettlementCannotCompleteTheForegroundWait() {
        var now = 0L
        val trace = AnswerJourneyTrace("test", { now })
        trace.mark(AnswerJourneyPhase.SYSTEMUI_ACTION_TAPPED)
        now += 10_000_000
        trace.mark(AnswerJourneyPhase.SHADE_COLLAPSED)
        trace.mark(AnswerJourneyPhase.WAIT_STARTED, AnswerJourneyWait.ACTIVITY_FOREGROUND)
        now += 5_000_000_000L
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.ACTIVITY_FOREGROUND)
        val report = JSONObject(trace.json("example", false))
        assertEquals(5010.0, report.getDouble("elapsedMs"), 0.0)
        assertEquals("ACTIVITY_FOREGROUND", report.getString("timedOutWait"))
        assertEquals("SHADE_COLLAPSED", report.getString("lastReachedPhase"))
        assertEquals(10.0, report.getJSONObject("firstElapsedMs").getDouble("SHADE_COLLAPSED"), 0.0)
        assertEquals("SYSTEMUI_ACTION_TAPPED", report.getJSONArray("phases").getJSONObject(0).getString("phase"))
    }

    @Test fun timeoutRetainsNamedWaitAndElapsedPhaseTimes() {
        var now = 1_000_000L
        val trace = AnswerJourneyTrace("test", { now })
        trace.mark(AnswerJourneyPhase.SYSTEMUI_ACTION_TAPPED)
        now += 3_000_000
        trace.mark(AnswerJourneyPhase.ACTIVITY_NEW_INTENT, token = 1, tokenPresent = true)
        now += 5_000_000_000L
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.ACTIVITY_FOREGROUND)
        val report = JSONObject(trace.json("example", false))
        assertEquals("ACTIVITY_FOREGROUND", report.getString("timedOutWait"))
        assertEquals("WAIT_TIMED_OUT", report.getString("lastPhase"))
        assertEquals(5003.0, report.getDouble("elapsedMs"), 0.0)
        val phases = report.getJSONArray("phases")
        assertEquals("SYSTEMUI_ACTION_TAPPED", phases.getJSONObject(0).getString("phase"))
        assertEquals(3.0, phases.getJSONObject(1).getDouble("sincePreviousMs"), 0.0)
        assertEquals(1, phases.getJSONObject(1).getInt("tokenOrdinal"))
        assertTrue(phases.getJSONObject(1).getBoolean("tokenPresent"))
    }

    @Test fun boundedEventsRetainFirstPhaseTimesAndTheLatestObservation() {
        var now = 0L
        val trace = AnswerJourneyTrace("test", { now })
        trace.mark(AnswerJourneyPhase.CORE_SET_ACTIVE_STARTED)
        repeat(1000) {
            now += 1_000_000
            trace.mark(AnswerJourneyPhase.TELECOM_READ_RETURNED)
        }
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.TELECOM_ACTIVE)
        val report = JSONObject(trace.json("example", false))
        assertEquals(256, report.getJSONArray("phases").length())
        assertTrue(report.getInt("droppedEvents") > 0)
        assertEquals(0.0, report.getJSONObject("firstElapsedMs").getDouble("CORE_SET_ACTIVE_STARTED"), 0.0)
        assertEquals("TELECOM_ACTIVE", report.getString("timedOutWait"))
        assertEquals("WAIT_TIMED_OUT", report.getString("lastPhase"))
    }

    @Test fun cleanupTimeoutCannotReplaceTheOriginalTimedOutWait() {
        var now = 0L
        val trace = AnswerJourneyTrace("test", { now })
        trace.mark(AnswerJourneyPhase.ACTIVITY_CREATED)
        now += 5_000_000_000L
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.ACTIVITY_FOREGROUND)
        trace.mark(AnswerJourneyPhase.CLEANUP_STARTED)
        now += 5_000_000_000L
        trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, AnswerJourneyWait.ACTIVITY_DESTROYED)
        val report = JSONObject(trace.json("example", false))
        assertEquals("ACTIVITY_FOREGROUND", report.getString("timedOutWait"))
        assertEquals("ACTIVITY_DESTROYED", report.getString("lastTimedOutWait"))
        assertEquals("CLEANUP_STARTED", report.getString("lastReachedPhase"))
    }
}
