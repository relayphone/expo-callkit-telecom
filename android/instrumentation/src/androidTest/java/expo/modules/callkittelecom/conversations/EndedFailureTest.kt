package expo.modules.callkittelecom.conversations

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndedFailureTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS)

    @Test fun onlyAFailedEndCarriesItsCause() {
        assertEquals(mapOf("at" to "now", "reason" to "failed", "failure" to mapOf("kind" to "notPlaced")),
            conversationEnd("now", "failed", EndedFailure.NOT_PLACED))
        assertEquals(mapOf("at" to "now", "reason" to "failed"), conversationEnd("now", "failed", null))
        assertEquals(mapOf("at" to "now", "reason" to "remoteEnded"),
            conversationEnd("now", "remoteEnded", EndedFailure.SERVER))
    }

    @Test fun kindsUseTheTypeScriptNames() {
        assertEquals(listOf("notPlaced", "media", "connectTimedOut", "server"), EndedFailure.values().map { it.kind })
    }

    @Test fun onlyAHandlerThatDidNotPlaceTheCallIsNotPlaced() {
        assertEquals(EndedFailure.NOT_PLACED, EndedFailure.ofHandler(ConversationFailure("handler_failed")))
        assertEquals(EndedFailure.NOT_PLACED, EndedFailure.ofHandler(ConversationFailure("deadline_exceeded")))
        assertNull(EndedFailure.ofHandler(ConversationFailure("conversation", 1)))
        assertNull(EndedFailure.ofHandler(IllegalStateException("unknown_conversation")))
    }

    @Test fun rejectedStartEndsAsNotPlaced() = journey(settles = false) { engine, snapshots ->
        val error = runCatching {
            engine.start(mapOf("to" to mapOf("id" to "rejected-callee"), "video" to false))
        }.exceptionOrNull()
        assertEquals("handler_failed", (error as ConversationFailure).code)
        val end = ended(snapshots) { (it["members"] as List<*>).firstOrNull() == mapOf("id" to "rejected-callee") }
        assertEquals("failed", end?.get("reason"))
        assertEquals(mapOf("kind" to "notPlaced"), end?.get("failure"))
    }

    @Test fun rejectedJoinEndsAsNotPlaced() = journey(settles = false) { engine, snapshots ->
        val id = engine.incoming(invitation("rejected-join"))["id"] as String
        val error = runCatching { engine.join(id) }.exceptionOrNull()
        assertEquals("handler_failed", (error as ConversationFailure).code)
        assertEquals(mapOf("kind" to "notPlaced"), ended(snapshots) { it["id"] == id }?.get("failure"))
    }

    @Test fun reportedFailureEndsAsServer() = journey(settles = true) { engine, snapshots ->
        val id = engine.start(mapOf("to" to mapOf("id" to "reported-callee"), "video" to false))
        engine.report(id, mapOf("type" to "ended", "reason" to "failed"))
        assertEquals(mapOf("kind" to "server"), ended(snapshots) { it["id"] == id }?.get("failure"))
    }

    @Test fun serverEndSignalFailureEndsAsServer() = journey(settles = true) { engine, snapshots ->
        val id = engine.incoming(invitation("server-failure"))["id"] as String
        assertTrue(engine.receiveCallEnded(mapOf("eventId" to "server-failure-end",
            "serverCallId" to "server-failure-call", "reason" to "failed")))
        withTimeout(5000) { while (ended(snapshots) { it["id"] == id } == null) delay(10) }
        assertEquals(mapOf("kind" to "server"), ended(snapshots) { it["id"] == id }?.get("failure"))
    }

    @Test fun remoteEndCarriesNoFailure() = journey(settles = true) { engine, snapshots ->
        val id = engine.start(mapOf("to" to mapOf("id" to "remote-callee"), "video" to false))
        engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
        val end = ended(snapshots) { it["id"] == id }
        assertEquals("remoteEnded", end?.get("reason"))
        assertNull(end?.get("failure"))
    }

    @Test fun nativeMediaFailureEndsAsMedia() = journey(settles = true,
        policy = mapOf("type" to "native", "media" to "webrtc", "prewarm" to true)) { engine, snapshots ->
        val previous = NativeMediaRegistry.factories["webrtc"]
        NativeMediaRegistry.factories["webrtc"] = {
            object : NativeMediaSession {
                override suspend fun prewarm(payload: Map<String, Any?>) { error("This session has no media") }
                override suspend fun activate() {}
                override suspend fun setHeld(held: Boolean) {}
                override suspend fun setMuted(muted: Boolean) {}
                override suspend fun close(reason: String) {}
            }
        }
        try {
            val id = engine.incoming(invitation("media-failure"))["id"] as String
            engine.join(id)
            withTimeout(10_000) { while (ended(snapshots) { it["id"] == id } == null) delay(10) }
            assertEquals(mapOf("kind" to "media"), ended(snapshots) { it["id"] == id }?.get("failure"))
        } finally {
            if (previous != null) NativeMediaRegistry.factories["webrtc"] = previous
            else NativeMediaRegistry.factories.remove("webrtc")
        }
    }

    /** Runs [body] on main with a handler that resolves or rejects every action, and the snapshots it sees. */
    private fun journey(settles: Boolean, policy: Map<String, Any?> = mapOf("type" to "handler"),
                        body: suspend (ConversationEngine, List<Map<String, Any?>>) -> Unit): Unit = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
            val previous = engine.policy
            val snapshots = mutableListOf<Map<String, Any?>>()
            engine.policy = JSONObject(policy)
            engine.actions.resetBridge()
            engine.emit = { name, event ->
                if (name == "conversationsChanged") snapshots += event
                if (name == "handlerAction") engine.actions.settle(event["actionID"] as String,
                    event["generation"] as Int, settles, engine.actions.epoch)
            }
            engine.actions.attach(true, 1, engine.actions.epoch)
            try { withTimeout(20_000) { body(engine, snapshots) } }
            finally { engine.policy = previous }
        }
    }

    private fun invitation(name: String) = mapOf("eventId" to "$name-event", "serverCallId" to "$name-call",
        "caller" to mapOf("handleType" to "generic", "value" to "$name-caller", "displayName" to "Caller"),
        "hasVideo" to false)

    /** The `ended` of the first snapshot that showed a matching conversation as ended. */
    private fun ended(snapshots: List<Map<String, Any?>>, matches: (Map<*, *>) -> Boolean): Map<*, *>? =
        snapshots.flatMap { it["conversations"] as List<*> }.map { it as Map<*, *> }
            .firstOrNull { it["status"] == "ended" && matches(it) }?.get("ended") as? Map<*, *>
}
