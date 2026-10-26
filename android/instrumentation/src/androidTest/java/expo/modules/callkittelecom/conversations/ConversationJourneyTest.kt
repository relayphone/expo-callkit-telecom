package expo.modules.callkittelecom.conversations

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS)

    @Test fun incomingAndOutgoingJourney() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = ConversationEngine.get(context)
            engine.policy = JSONObject(mapOf("type" to "handler"))
            val seen = mutableListOf<String>()
            engine.emit = { name, body ->
                if (name == "handlerAction") engine.actions.settle(body["actionID"] as String,
                    body["generation"] as Int, true)
            }
            engine.actions.attach(true, 1)
            fun status(id: String): String? {
                val states = engine.snapshot["conversations"] as List<*>
                return (states.firstOrNull { (it as Map<*, *>)["id"] == id } as? Map<*, *>)?.get("status") as? String
            }
            fun expect(id: String, expected: String) {
                assertEquals(expected, status(id))
                seen += expected
            }
            withTimeout(30_000) {
                val payload = mapOf("eventId" to "journey-event", "serverCallId" to "journey-incoming",
                    "caller" to mapOf("id" to "caller", "displayName" to "Emulator caller"), "hasVideo" to false)
                val outcome = engine.incoming(payload)
                assertEquals("reported", outcome["status"])
                val id = outcome["id"] as String
                assertEquals(CallIdentity.forServerCallId("journey-incoming").toString(), id)
                expect(id, "ringing")
                assertEquals(id, engine.incoming(payload)["id"])
                engine.join(id)
                expect(id, "connecting")
                engine.report(id, mapOf("type" to "connected"))
                expect(id, "connected")
                engine.hold(id, true)
                expect(id, "held")
                engine.hold(id, false)
                expect(id, "connected")
                engine.end(id)
                assertNull(status(id))
                seen += "incoming-ended"
                val outgoing = engine.start(mapOf("to" to mapOf("id" to "callee"), "video" to false))
                expect(outgoing, "dialing")
                engine.report(outgoing, mapOf("type" to "connecting"))
                expect(outgoing, "connecting")
                engine.report(outgoing, mapOf("type" to "connected"))
                expect(outgoing, "connected")
                engine.end(outgoing)
                assertNull(status(outgoing))
                seen += "outgoing-ended"
            }
            assertEquals(listOf("ringing", "connecting", "connected", "held", "connected",
                "incoming-ended", "dialing", "connecting", "connected", "outgoing-ended"), seen)
            File(context.filesDir, "journey-results.json").writeText(JSONObject(mapOf(
                "passed" to true, "steps" to seen, "identityVectors" to "call-identity.json")).toString())
        }
    }

    @Test fun nativeDeadlineAndReplacement() = runBlocking {
        withContext(Dispatchers.Main) {
            val deliveries = mutableListOf<Map<String, Any?>>()
            val coordinator = ActionCoordinator({ name, body -> if (name == "handlerAction") deliveries += body }, {})
            val conversation = mapOf<String, Any?>("id" to "test-conversation")
            val pending = async { coordinator.perform(conversation, "join", "system") }
            yield()
            assertNotNull(coordinator.pending("test-conversation"))
            coordinator.attach(true, 1)
            assertEquals(1, deliveries.size)
            coordinator.attach(true, 2)
            assertEquals(2, deliveries.size)
            val id = deliveries[0]["actionID"] as String
            coordinator.settle(id, 1, false)
            assertFalse(pending.isCompleted)
            coordinator.settle(id, 2, true)
            pending.await()
            assertNull(coordinator.pending("test-conversation"))
            val began = android.os.SystemClock.elapsedRealtime()
            val failure = runCatching { coordinator.perform(conversation, "join", "system") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(android.os.SystemClock.elapsedRealtime() - began in 3300..4500)
            assertNull(coordinator.pending("test-conversation"))
        }
    }
}
