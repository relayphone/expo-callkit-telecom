package expo.modules.callkittelecom.conversations

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnswerJourneyTraceTest {
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
