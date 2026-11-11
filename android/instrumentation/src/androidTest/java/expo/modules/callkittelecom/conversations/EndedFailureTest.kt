package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
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
        assertEquals(listOf("notPlaced", "media", "frameworkReset", "connectTimedOut", "server", "filtered", "filtered", "filtered"),
            EndedFailure.entries.map { it.kind })
    }

    @Test fun filteredFailuresEncodeThePackageCause() {
        for ((reason, cause) in listOf(NativeIncomingFilterReason.DO_NOT_DISTURB to "doNotDisturb",
            NativeIncomingFilterReason.BLOCK_LIST to "blockList", NativeIncomingFilterReason.OTHER to "other")) {
            val failure = EndedFailure.filtered(reason)
            assertEquals(mapOf("at" to "now", "reason" to "failed",
                "failure" to mapOf("kind" to "filtered", "cause" to cause)), conversationEnd("now", "failed", failure))
            assertEquals(mapOf("at" to "now", "reason" to "local"), conversationEnd("now", "local", failure))
        }
    }

    @Test fun filteredIncomingEndsWithoutAUserActionOrWarmup() = journey(settles = true) { engine, snapshots ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousFilter = NativeIncomingFilters.evaluate
        val previousEmitter = engine.emit
        val events = mutableListOf<Map<String, Any?>>()
        var warmups = 0
        engine.emit = { name, event ->
            previousEmitter(name, event)
            if (name == "conversationEvent") events += event
        }
        try {
            for ((reason, cause) in listOf(NativeIncomingFilterReason.DO_NOT_DISTURB to "doNotDisturb",
                NativeIncomingFilterReason.BLOCK_LIST to "blockList", NativeIncomingFilterReason.OTHER to "other")) {
                NativeIncomingFilters.evaluate = { NativeIncomingFilter(reason) }
                val payload = invitation("filtered-$cause-${System.nanoTime()}")
                val id = payload["serverCallId"] as String
                val result = NativeCallPush(engine) { warmups++ }.handle(mapOf("messageType" to "incomingCall",
                    "incomingCall" to JSONObject(payload).toString()), true)!!
                assertEquals(mapOf("status" to "filtered", "reason" to cause), result)
                val end = ended(snapshots) { it["id"] == id }
                assertEquals("failed", end?.get("reason"))
                assertEquals(mapOf("kind" to "filtered", "cause" to cause), end?.get("failure"))
                val outcomes = events.filter { it["type"] == "incomingConversationOutcome" &&
                    (it["payload"] as? Map<*, *>)?.get("serverCallId") == id }
                assertEquals(1, outcomes.size)
                assertEquals(result, (outcomes.single()["payload"] as Map<*, *>)["outcome"])
                assertFalse(events.any { it["type"] == "actionPerformed" &&
                    (it["payload"] as? Map<*, *>)?.get("conversationId") == id })
                assertFalse((engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id })
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(25) }
                assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.notification.category == Notification.CATEGORY_CALL })
            }
            assertEquals(0, warmups)
        } finally {
            engine.emit = previousEmitter
            NativeIncomingFilters.evaluate = previousFilter
        }
    }

    @Test fun onlyAHandlerThatDidNotPlaceTheCallIsNotPlaced() {
        assertEquals(EndedFailure.NOT_PLACED, EndedFailure.ofHandler(ConversationFailure("handler_failed")))
        assertEquals(EndedFailure.NOT_PLACED, EndedFailure.ofHandler(ConversationFailure("deadline_exceeded")))
        assertNull(EndedFailure.ofHandler(ConversationFailure("conversation", 1)))
        assertNull(EndedFailure.ofHandler(IllegalStateException("unknown_conversation")))
    }

    @Test fun rejectedStartEndsAsNotPlaced() = journey(settles = false) { engine, snapshots ->
        val error = runCatching {
            engine.start(mapOf("id" to "outgoing-${System.nanoTime()}", "to" to mapOf("id" to "rejected-callee"), "video" to false))
        }.exceptionOrNull()
        assertEquals("handler_failed", (error as ConversationFailure).code)
        val end = ended(snapshots) { (it["members"] as List<*>).firstOrNull() == mapOf("id" to "rejected-callee") }
        assertEquals("failed", end?.get("reason"))
        assertEquals(mapOf("kind" to "notPlaced"), end?.get("failure"))
    }

    @Test fun rejectedPlacementHandlerDoesNotGateNativeAnswer() = journey(settles = false) { engine, snapshots ->
        val id = engine.incoming(invitation("native-answer"))["id"] as String
        engine.join(id)
        assertNull(ended(snapshots) { it["id"] == id })
        engine.end(id)
    }

    @Test fun reportedFailureEndsAsServer() = journey(settles = true) { engine, snapshots ->
        val id = engine.start(mapOf("id" to "outgoing-${System.nanoTime()}", "to" to mapOf("id" to "reported-callee"), "video" to false))
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
        val id = engine.start(mapOf("id" to "outgoing-${System.nanoTime()}", "to" to mapOf("id" to "remote-callee"), "video" to false))
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
            val previousMedia = engine.nativeMedia
            val previous = engine.policy
            val snapshots = mutableListOf<Map<String, Any?>>()
            engine.nativeMedia = if (policy["type"] == "native") AndroidNativeMedia.LIVEKIT else AndroidNativeMedia.NONE
            engine.policy = JSONObject(policy)
            engine.actions.resetBridge()
            engine.emit = { name, event ->
                if (name == "conversationsChanged") snapshots += event
                if (name == "handlerAction") engine.actions.settle(event["actionID"] as String,
                    event["generation"] as Int, settles, engine.actions.epoch)
            }
            engine.actions.attach(true, 1, engine.actions.epoch)
            try { withTimeout(20_000) { body(engine, snapshots) } }
            finally { engine.nativeMedia = previousMedia; engine.policy = previous }
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
