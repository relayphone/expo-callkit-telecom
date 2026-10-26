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
            File(context.filesDir, "journey-results.json").delete()
            val engine = ConversationEngine.get(context)
            engine.policy = JSONObject(mapOf("type" to "handler"))
            val baselineJobs = engine.jobs.coroutineContext[Job]!!.children.count { it.isActive }
            val seen = mutableListOf<String>()
            engine.emit = { name, body ->
                if (name == "handlerAction") engine.actions.settle(body["actionID"] as String,
                    body["generation"] as Int, true, engine.actions.epoch)
            }
            engine.actions.attach(true, 1, engine.actions.epoch)
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
                val second = engine.incoming(payload + mapOf("eventId" to "second-event", "serverCallId" to "second-call"))
                assertEquals("reported", second["status"])
                val secondId = second["id"] as String
                assertNotEquals(id, secondId)
                assertEquals("ringing", status(secondId))
                assertEquals("held", status(id))
                assertEquals(2, (engine.snapshot["conversations"] as List<*>).size)
                seen += "second-ringing"
                engine.end(secondId)
                assertNull(status(secondId)); assertEquals("held", status(id))
                seen += "second-ended"
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
                withTimeout(5000) { while (engine.jobs.coroutineContext[Job]!!.children.count { it.isActive } > baselineJobs) delay(10) }
            }
            assertEquals(listOf("ringing", "connecting", "connected", "held", "second-ringing", "second-ended", "connected",
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
            coordinator.attach(true, 1, coordinator.epoch)
            assertEquals(1, deliveries.size)
            coordinator.attach(true, 2, coordinator.epoch)
            assertEquals(2, deliveries.size)
            val id = deliveries[0]["actionID"] as String
            coordinator.settle(id, 1, false, coordinator.epoch)
            assertFalse(pending.isCompleted)
            coordinator.settle(id, 2, true, coordinator.epoch)
            pending.await()
            assertNull(coordinator.pending("test-conversation"))
            val began = android.os.SystemClock.elapsedRealtime()
            val failure = runCatching { coordinator.perform(conversation, "join", "system") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(android.os.SystemClock.elapsedRealtime() - began in 3300..4500)
            assertNull(coordinator.pending("test-conversation"))
        }
    }
    @Test fun nativeAnswerDoesNotWaitForJavaScript() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = ConversationEngine.get(context)
            val order = mutableListOf<String>()
            val holding = CompletableDeferred<Unit>()
            val holdGate = CompletableDeferred<Unit>()
            NativeMediaRegistry.factories["webrtc"] = {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) {
                        val states = engine.snapshot["conversations"] as List<*>
                        assertTrue(states.isNotEmpty())
                        order += "prewarm-muted"
                    }
                    override suspend fun activate() { order += "activate" }
                    override suspend fun setHeld(held: Boolean) { if (held) { holding.complete(Unit); holdGate.await() } }
                    override suspend fun setMuted(muted: Boolean) {}
                    override suspend fun close(reason: String) { order += "close" }
                }
            }
            engine.policy = JSONObject(mapOf("type" to "native", "media" to "webrtc", "prewarm" to true))
            engine.actions.resetBridge()
            engine.emit = { name, _ -> assertNotEquals("handlerAction", name) }
            try {
                val outcome = engine.incoming(mapOf("eventId" to "native-event", "serverCallId" to "native-call",
                    "caller" to mapOf("id" to "native-caller"), "hasVideo" to false))
                assertEquals("reported", outcome["status"])
                val id = outcome["id"] as String
                val began = android.os.SystemClock.elapsedRealtime()
                engine.join(id)
                assertTrue(android.os.SystemClock.elapsedRealtime() - began < 1500)
                withTimeout(5000) { while (!order.contains("activate")) delay(10) }
                assertEquals(listOf("prewarm-muted", "activate"), order)
                engine.emit = { name, body ->
                    if (name == "handlerAction") engine.actions.settle(body["actionID"] as String,
                        body["generation"] as Int, true, engine.actions.epoch)
                }
                engine.actions.attach(true, 1, engine.actions.epoch)
                val pause = async { engine.hold(id, true) }
                withTimeout(3000) { holding.await() }
                engine.report(id, mapOf("type" to "connected"))
                assertEquals("held", ((engine.snapshot["conversations"] as List<*>).single() as Map<*, *>)["status"])
                holdGate.complete(Unit); pause.await()
                engine.hold(id, false)
                engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(3000) { while (!order.contains("close")) delay(10) }
            } finally { NativeMediaRegistry.factories.remove("webrtc") }
        }
    }

    @Test fun terminationCancelsAnswerAndFetchFallbackIsNative() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val ready = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            var activations = 0
            var fetched = false
            NativeMediaRegistry.fetchers["fixture-fetcher"] = {
                fetched = true
                mapOf("provider" to "livekit", "expiresAt" to System.currentTimeMillis() / 1000 + 120)
            }
            NativeMediaRegistry.factories["webrtc"] = {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) { assertTrue(fetched); ready.complete(Unit) }
                    override suspend fun activate() { activations++ }
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(muted: Boolean) {}
                    override suspend fun close(reason: String) { closed.complete(Unit) }
                }
            }
            try {
                val policy = JSONObject(mapOf("media" to "webrtc", "fallback" to mapOf("name" to "fixture-fetcher")))
                val controller = NativeMediaController(context, emptyMap(), policy, owner, {}, {})
                controller.prewarm()
                withTimeout(3000) { ready.await() }
                controller.answered { entered.complete(Unit); gate.await() }
                withTimeout(3000) { entered.await() }
                controller.close("ended")
                withTimeout(3000) { closed.await() }
                gate.complete(Unit)
                delay(50)
                assertEquals(0, activations)
            } finally {
                NativeMediaRegistry.factories.remove("webrtc")
                NativeMediaRegistry.fetchers.remove("fixture-fetcher")
                owner.cancel()
            }
        }
    }

    @Test fun joinedCallWithoutConnectedReportExpires() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
            engine.policy = JSONObject(mapOf("type" to "handler"))
            engine.emit = { name, body ->
                if (name == "handlerAction") engine.actions.settle(body["actionID"] as String,
                    body["generation"] as Int, true, engine.actions.epoch)
            }
            engine.actions.attach(true, 1, engine.actions.epoch)
            val previous = engine.answerTimeoutMillis
            try {
                engine.answerTimeoutMillis = 200
                val outcome = engine.incoming(mapOf("eventId" to "timeout-event", "serverCallId" to "timeout-call",
                    "caller" to mapOf("id" to "caller")))
                assertEquals("reported", outcome["status"])
                val id = outcome["id"] as String
                engine.join(id)
                withTimeout(4000) {
                    while ((engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }) delay(10)
                }
            } finally { engine.answerTimeoutMillis = previous }
        }
    }

}
