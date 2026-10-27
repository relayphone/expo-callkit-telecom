package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.os.SystemClock
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
class AnsweredElsewhereOwnershipTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun invitation(key: String) = mapOf("eventId" to "incoming-$key", "serverCallId" to key,
        "caller" to mapOf("handleType" to "generic", "value" to key, "displayName" to key), "hasVideo" to false)
    private fun ended(key: String) = mapOf("eventId" to "ended-$key", "serverCallId" to key, "reason" to "answeredElsewhere")
    private fun present(engine: ConversationEngine, id: String) = (engine.snapshot["conversations"] as List<*>)
        .any { (it as Map<*, *>)["id"] == id }
    private suspend fun removed(engine: ConversationEngine, id: String) = withTimeout(5000) {
        while (present(engine, id) || TelecomEvidence.calls().isNotEmpty()) delay(20)
    }

    @Test fun anEndWhileCoreAnswerIsPendingCannotRunTheAnswerContinuation() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val oldPolicy = engine.policy; val oldEmitter = engine.emit
            val key = "elsewhere-answer-${SystemClock.elapsedRealtime()}"
            val id = CallIdentity.forServerCallId(key).toString()
            val observations = mutableListOf<NativeMediaSignal>()
            val endReceived = CompletableDeferred<Unit>()
            var joining: Deferred<Result<Unit>>? = null
            var settledJoin = false; var pendingCoreAnswer = false; var accepted = false
            var endNanos = 0L; var events = 0; var handlers = 0; var warmups = 0
            engine.policy = JSONObject(mapOf("type" to "handler", "prewarm" to false))
            engine.actions.resetBridge(); engine.actions.attach(true, 1, engine.actions.epoch)
            engine.emit = { name, body ->
                if (name == "handlerAction") {
                    if (body["kind"] == "join") {
                        engine.actions.settle(body["actionID"] as String, body["generation"] as Int, true, engine.actions.epoch)
                        settledJoin = true
                    } else handlers++
                }
                if (name == "conversationEvent" && body["type"] == "answeredElsewhere") events++
            }
            val observer = NativeMediaObserver { observation ->
                if (observation.serverCallId == key) {
                    observations += observation
                    if (observation.stage == NativeMediaStage.ANSWER_REQUESTED && endNanos == 0L) {
                        // Handler delivery is posted; this must run after synchronous JS acceptance
                        // while the actual Core answer RPC is still awaiting its result.
                        pendingCoreAnswer = settledJoin && joining?.isCompleted == false
                        endNanos = SystemClock.elapsedRealtimeNanos()
                        accepted = engine.receiveCallEnded(ended(key))
                        endReceived.complete(Unit)
                    }
                }
            }
            NativeMediaSignals.observers += observer
            try {
                val incoming = NativeCallPush(engine) { warmups++ }.handle(mapOf(
                    "messageType" to "incomingCall", "incomingCall" to JSONObject(invitation(key)).toString()), true)
                assertEquals("reported", incoming!!["status"])
                val initialWarmups = warmups
                joining = async { runCatching { engine.join(id) } }
                withTimeout(3000) { endReceived.await() }
                assertTrue("The regression must actually interrupt a settled handler's pending Core answer", pendingCoreAnswer)
                assertTrue(accepted)
                assertTrue("The cancelled native answer continuation must reject", withTimeout(3000) { joining!!.await() }.isFailure)
                removed(engine, id)
                // Drain posted native observations before checking their original timestamps.
                yield(); delay(25)
                assertFalse(observations.any { it.elapsedRealtimeNanos >= endNanos && it.stage in setOf(
                    NativeMediaStage.ANSWER_ACCEPTED, NativeMediaStage.PREWARM_STARTED, NativeMediaStage.JS_WARM_STARTED) })
                assertEquals(1, events); assertEquals(0, handlers); assertEquals(initialWarmups, warmups)
                assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.notification.category == Notification.CATEGORY_CALL &&
                        it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == key
                })
            } finally {
                NativeMediaSignals.observers -= observer
                joining?.cancelAndJoin()
                if (present(engine, id)) runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = oldEmitter; engine.policy = oldPolicy; engine.actions.resetBridge()
            }
        }
    }

    @Test fun anEndReceivedBeforeActualCoreAdmissionIsRetainedUntilTheCallIsReported() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val oldPolicy = engine.policy; val oldEmitter = engine.emit
            val key = "elsewhere-admission-${SystemClock.elapsedRealtime()}"
            val id = CallIdentity.forServerCallId(key).toString()
            val admitted = CompletableDeferred<Long>()
            var receivedNanos = 0L; var acceptedNanos = 0L
            var accepted = false; var events = 0; var handlers = 0; var warmups = 0
            val observer = NativeMediaObserver { observation ->
                if (observation.serverCallId == key) when (observation.stage) {
                    NativeMediaStage.CORE_ADD_REQUESTED -> {
                        receivedNanos = SystemClock.elapsedRealtimeNanos()
                        accepted = engine.receiveCallEnded(ended(key))
                        acceptedNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    NativeMediaStage.CORE_ADMITTED -> admitted.complete(observation.elapsedRealtimeNanos)
                    else -> Unit
                }
            }
            engine.policy = JSONObject(mapOf("type" to "handler", "prewarm" to false))
            engine.actions.resetBridge(); engine.actions.attach(true, 1, engine.actions.epoch)
            engine.emit = { name, body ->
                if (name == "handlerAction") handlers++
                if (name == "conversationEvent" && body["type"] == "answeredElsewhere") {
                    val payload = body["payload"] as Map<*, *>
                    assertEquals(id, payload["conversationId"])
                    assertEquals(key, payload["serverCallId"])
                    assertEquals("ended-$key", payload["eventId"])
                    events++
                }
            }
            NativeMediaSignals.observers += observer
            try {
                val reporting = withTimeout(6000) { NativeCallPush(engine) { warmups++ }.handle(mapOf(
                    "messageType" to "incomingCall", "incomingCall" to JSONObject(invitation(key)).toString()), true) }
                val admissionNanos = withTimeout(3000) { admitted.await() }
                assertTrue("The pending incoming owner must accept termination", accepted)
                assertTrue("The test must actually receive the end before admission", receivedNanos > 0 && receivedNanos < admissionNanos)
                assertTrue("Acceptance must precede the actual CORE_ADMITTED timestamp, not observer delivery", acceptedNanos < admissionNanos)
                assertEquals("reported", reporting!!["status"])
                removed(engine, id)
                assertEquals(1, events); assertEquals(0, handlers); assertEquals(0, warmups)
                assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.notification.category == Notification.CATEGORY_CALL &&
                        it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == key
                })
            } finally {
                NativeMediaSignals.observers -= observer
                if (present(engine, id)) runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = oldEmitter; engine.policy = oldPolicy; engine.actions.resetBridge()
            }
        }
    }

    @Test fun anEndRetainedDuringIncomingPolicyCannotRestartNotificationOrJavaScript() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val oldPolicy = engine.policy; val oldEmitter = engine.emit
            val oldFilter = NativeIncomingFilters.evaluate
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val key = "elsewhere-inflight-${SystemClock.elapsedRealtime()}"
            val id = CallIdentity.forServerCallId(key).toString()
            var warmups = 0; var events = 0; var handlerCalls = 0
            engine.policy = JSONObject(mapOf("type" to "handler", "prewarm" to false))
            engine.actions.resetBridge(); engine.actions.attach(true, 1, engine.actions.epoch)
            engine.emit = { name, body ->
                if (name == "handlerAction") handlerCalls++
                if (name == "conversationEvent" && body["type"] == "answeredElsewhere") events++
            }
            NativeIncomingFilters.evaluate = { entered.complete(Unit); release.await(); null }
            try {
                val reporting = async { NativeCallPush(engine) { warmups++ }.handle(mapOf(
                    "messageType" to "incomingCall", "incomingCall" to JSONObject(invitation(key)).toString()), true) }
                withTimeout(5000) { entered.await() }
                assertTrue(engine.receiveCallEnded(ended(key)))
                release.complete(Unit)
                assertEquals("reported", withTimeout(5000) { reporting.await() }!!["status"])
                removed(engine, id)
                assertEquals(1, events); assertEquals(0, warmups); assertEquals(0, handlerCalls)
                assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.notification.category == Notification.CATEGORY_CALL &&
                        it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == key
                })
            } finally {
                release.complete(Unit); NativeIncomingFilters.evaluate = oldFilter
                if (present(engine, id)) runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = oldEmitter; engine.policy = oldPolicy; engine.actions.resetBridge()
            }
        }
    }

    @Test fun connectedIncomingAndReentrantObserversKeepExactlyOneTerminalEvent() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val oldPolicy = engine.policy; val oldEmitter = engine.emit
            val key = "elsewhere-connected-${SystemClock.elapsedRealtime()}"
            val signal = ended(key)
            val laterSignal = signal + ("eventId" to "later-$key") + ("reason" to "remoteEnded")
            val id = CallIdentity.forServerCallId(key).toString()
            var terminalSeen = false; var events = 0; var joins = 0; var ends = 0
            var laterAccepted = false
            engine.policy = JSONObject(mapOf("type" to "handler", "prewarm" to false))
            engine.actions.resetBridge(); engine.actions.attach(true, 1, engine.actions.epoch)
            engine.emit = { name, body ->
                if (name == "handlerAction") {
                    if (body["kind"] == "join") {
                        joins++
                        engine.actions.settle(body["actionID"] as String, body["generation"] as Int, true, engine.actions.epoch)
                    }
                    if (body["kind"] == "end") ends++
                }
                if (name == "conversationsChanged") {
                    val ended = (body["conversations"] as List<*>).any {
                        val state = it as Map<*, *>
                        state["id"] == id && state["status"] == "ended" &&
                            (state["ended"] as? Map<*, *>)?.get("reason") == "answeredElsewhere"
                    }
                    if (ended) {
                        terminalSeen = true
                        assertFalse(engine.receiveCallEnded(signal))
                        if (!laterAccepted) {
                            laterAccepted = true
                            assertTrue("A distinct event still matches retained incoming ownership", engine.receiveCallEnded(laterSignal))
                        }
                    }
                }
                if (name == "conversationEvent" && body["type"] == "answeredElsewhere") {
                    assertTrue(terminalSeen); events++
                    assertFalse(engine.receiveCallEnded(signal))
                    assertEquals(id, (body["payload"] as Map<*, *>)["conversationId"])
                    assertEquals(signal["eventId"], (body["payload"] as Map<*, *>)["eventId"])
                    assertEquals(key, (body["payload"] as Map<*, *>)["serverCallId"])
                    val canonical = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
                        .first { it["id"] == id }
                    assertEquals("answeredElsewhere", (canonical["ended"] as Map<*, *>)["reason"])
                }
            }
            try {
                assertEquals("reported", engine.incoming(invitation(key))["status"])
                engine.join(id)
                engine.report(id, mapOf("type" to "connected"))
                assertTrue((engine.snapshot["conversations"] as List<*>).any {
                    val state = it as Map<*, *>
                    state["id"] == id && state["status"] == "connected"
                })
                assertTrue(engine.receiveCallEnded(signal))
                removed(engine, id)
                assertTrue(terminalSeen); assertEquals(1, events); assertEquals(1, joins); assertEquals(0, ends)
                assertTrue(laterAccepted); assertFalse(engine.receiveCallEnded(laterSignal))
            } finally {
                if (present(engine, id)) runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = oldEmitter; engine.policy = oldPolicy; engine.actions.resetBridge()
            }
        }
    }
}
