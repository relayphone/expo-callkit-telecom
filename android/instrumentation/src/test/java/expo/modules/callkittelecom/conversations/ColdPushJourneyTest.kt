package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ColdPushJourneyTest {
    private val request = PushFaultDelivery("cold-delivery", "cold-call", "cold-invite")

    @Test fun eachBoundaryKeepsTheSameDeliveryAndMonotonicTime() = runBlocking {
        var now = 10L
        var contents = ""
        val log = PushFaultLog(123) { contents = it }
        val steps = mutableListOf<String>()
        ColdPushJourney({ now++ }, log::record).run(request, 1,
            { steps += "intake"; true }, { steps += "ringing" }, { steps += "presented" },
            { steps += "answer" }, { steps += "cleanup" })
        assertEquals(listOf("intake", "ringing", "presented", "answer", "cleanup"), steps)
        val events = JSONObject(contents).getJSONArray("events")
        assertEquals(6, events.length())
        assertEquals(listOf("processStarted", "intake", "ringing", "presented", "answered", "cleaned"),
            (0 until events.length()).map { events.getJSONObject(it).getString("stage") })
        assertEquals(listOf("1", "10", "11", "12", "13", "14"),
            (0 until events.length()).map { events.getJSONObject(it).getString("elapsedRealtimeNanos") })
        for (i in 0 until events.length()) {
            assertEquals("cold-delivery", events.getJSONObject(i).getString("deliveryId"))
            assertEquals("cold-call", events.getJSONObject(i).getString("callId"))
            assertEquals("cold-invite", events.getJSONObject(i).getString("eventId"))
        }
    }

    @Test fun rejectedIntakeAndMissingPresentationOrAnswerStayFatalAndAlwaysCleanUp() = runBlocking {
        for (step in listOf("intake", "ringing", "presented", "answer", "cleanup")) {
            val events = mutableListOf<PushFaultEvent.ColdStart>()
            var cleaned = false
            val failure = IllegalStateException("controlled failure")
            val result = runCatching {
                ColdPushJourney({ 10 }, { events += it }).run(request, 1,
                    { step != "intake" }, { if (step == "ringing") throw failure },
                    { if (step == "presented") throw failure }, { if (step == "answer") throw failure },
                    { cleaned = true; if (step == "cleanup") throw failure })
            }
            assertTrue(result.isFailure)
            assertTrue(cleaned)
            assertTrue(events.any { it.stage == ColdPushStage.FAILED })
            if (step != "cleanup") assertEquals(ColdPushStage.CLEANED, events.last().stage)
            if (step == "presented" || step == "ringing")
                assertTrue(events.none { it.stage == ColdPushStage.ANSWERED })
        }
    }

    @Test fun cleanupFailureCannotReplaceTheOriginalFailure() = runBlocking {
        val failure = IllegalStateException("original")
        val result = runCatching {
            ColdPushJourney({ 10 }, {}).run(request, 1, { true }, {}, { throw failure }, {},
                { error("cleanup") })
        }
        assertSame(failure, result.exceptionOrNull())
    }
}
