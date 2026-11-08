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

    private suspend fun awaitTelecomRemoval() = withTimeout(5000) {
        while (TelecomEvidence.calls().isNotEmpty()) delay(10)
    }

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
                    "caller" to mapOf("handleType" to "generic", "value" to "caller", "displayName" to "Emulator caller"), "hasVideo" to false)
                val outcome = engine.incoming(payload)
                assertEquals("reported", outcome["status"])
                val id = outcome["id"] as String
                assertEquals("journey-incoming", id)
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
                val outgoing = engine.start(mapOf("id" to "outgoing-${System.nanoTime()}", "to" to mapOf("id" to "callee"), "video" to false))
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
                "passed" to true, "steps" to seen, "identityPolicy" to "raw-application-id")).toString())
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
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.policy = JSONObject(mapOf("type" to "native", "media" to "webrtc", "prewarm" to true))
            engine.actions.resetBridge()
            engine.emit = { name, _ -> assertNotEquals("handlerAction", name) }
            try {
                val outcome = engine.incoming(mapOf("eventId" to "native-event", "serverCallId" to "native-call",
                    "caller" to mapOf("handleType" to "generic", "value" to "native-caller", "displayName" to "Native caller"), "hasVideo" to false))
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
            } finally { NativeMediaRegistry.factories.remove("webrtc"); engine.nativeMedia = AndroidNativeMedia.NONE }
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
            val ended = mutableListOf<Map<*, *>>()
            engine.emit = { name, body ->
                if (name == "handlerAction") engine.actions.settle(body["actionID"] as String,
                    body["generation"] as Int, true, engine.actions.epoch)
                if (name == "conversationsChanged") {
                    for (state in body["conversations"] as List<*>) if ((state as Map<*, *>)["status"] == "ended") ended += state
                }
            }
            engine.actions.attach(true, 1, engine.actions.epoch)
            val previous = engine.answerTimeoutMillis
            try {
                engine.answerTimeoutMillis = 200
                val outcome = engine.incoming(mapOf("eventId" to "timeout-event", "serverCallId" to "timeout-call",
                    "caller" to mapOf("handleType" to "generic", "value" to "caller", "displayName" to "Caller")))
                assertEquals("reported", outcome["status"])
                val id = outcome["id"] as String
                engine.join(id)
                withTimeout(4000) {
                    while ((engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }) delay(10)
                }
                val end = ended.first { it["id"] == id }["ended"] as Map<*, *>
                assertEquals("failed", end["reason"])
                assertEquals(mapOf("kind" to "connectTimedOut"), end["failure"])
                awaitTelecomRemoval()
            } finally { engine.answerTimeoutMillis = previous }
        }
    }

    @Test fun canonicalNativeActionsNeedNoHandlerAndEndPublishesInOrder(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
            val previous = engine.emit; val selection = engine.nativeMedia
            val events = mutableListOf<Map<String, Any?>>()
            val order = mutableListOf<String>()
            val id = "raw/電話 ${System.nanoTime()}"
            engine.nativeMedia = AndroidNativeMedia.NONE
            engine.actions.resetBridge()
            engine.emit = { name, body ->
                assertNotEquals("handlerAction", name)
                if (name == "conversationsChanged") order += ((body["conversations"] as List<*>).firstOrNull() as? Map<*, *>)?.get("status")?.toString() ?: "empty"
                if (name == "conversationEvent" && body["type"] == "actionPerformed") {
                    @Suppress("UNCHECKED_CAST") val payload = body["payload"] as Map<String, Any?>
                    events += payload; order += "performed-${(payload["action"] as Map<*, *>)["kind"]}"
                }
            }
            try {
                val payload = mapOf("eventId" to id, "serverCallId" to id,
                    "caller" to mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), "hasVideo" to false)
                val first = async(start = CoroutineStart.UNDISPATCHED) { engine.incoming(payload) }
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { engine.incoming(payload + ("eventId" to "duplicate-$id")) }
                val firstOutcome = first.await(); val duplicateOutcome = duplicate.await()
                assertEquals("First incoming: $firstOutcome", "reported", firstOutcome["status"])
                assertEquals("Duplicate incoming: $duplicateOutcome", "reported", duplicateOutcome["status"])
                assertEquals(id, firstOutcome["id"]); assertEquals(id, duplicateOutcome["id"])
                assertEquals("invalid_arguments", (runCatching { engine.start(mapOf("id" to id, "to" to mapOf("id" to "peer"))) }.exceptionOrNull() as ConversationFailure).code)
                engine.join(id)
                engine.report(id, mapOf("type" to "connected"))
                engine.hold(id, true); engine.hold(id, true)
                engine.hold(id, false)
                engine.mute(id, true); engine.mute(id, false, NativeActionOrigin.SYSTEM)
                assertEquals("unsupported", runCatching { engine.tones(id, "123") }.exceptionOrNull()?.message)
                engine.end(id)
                assertEquals(listOf("ended", "performed-end", "empty"), order.takeLast(3))
                val actions = listOf(mapOf("kind" to "answer"), mapOf("kind" to "hold", "held" to true),
                    mapOf("kind" to "hold", "held" to false), mapOf("kind" to "mute", "muted" to true),
                    mapOf("kind" to "mute", "muted" to false), mapOf("kind" to "end"))
                assertEquals(actions.mapIndexed { index, action -> mapOf("conversationId" to id,
                    "origin" to if (index == 4) "system" else "app", "action" to action) }, events)
                awaitTelecomRemoval()
            } finally {
                runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = previous; engine.nativeMedia = selection; engine.actions.resetBridge()
            }
        }
    }

    @Test fun slowPlacementPrecedesTelecomAndAdvertisesNineSeconds(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
            awaitTelecomRemoval()
            val previous = engine.emit
            val id = "slow/${System.nanoTime()}"
            val deliveries = mutableListOf<Map<String, Any?>>()
            engine.actions.resetBridge(); engine.actions.attach(true, 1, engine.actions.epoch)
            engine.emit = { name, body -> if (name == "handlerAction") deliveries += body }
            val before = java.time.Instant.now()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                engine.start(mapOf("id" to id, "to" to mapOf("id" to "peer"), "video" to false))
            }
            try {
                val delivery = deliveries.single()
                val milliseconds = java.time.Duration.between(before, java.time.Instant.parse(delivery["deadline"] as String)).toMillis()
                assertTrue(milliseconds in 8900..9200)
                assertTrue(TelecomEvidence.calls().isEmpty())
                val holdError = withTimeout(1000) { runCatching { engine.hold(id, true) }.exceptionOrNull() }
                assertEquals("invalid_arguments", holdError?.message)
                val collision = runCatching { engine.start(mapOf("id" to id, "to" to mapOf("id" to "peer"))) }.exceptionOrNull()
                assertEquals("invalid_arguments", (collision as ConversationFailure).code)
                delay(3750)
                assertFalse(pending.isCompleted); assertTrue(TelecomEvidence.calls().isEmpty())
                engine.actions.settle(delivery["actionID"] as String, 1, true, engine.actions.epoch)
                assertEquals(id, withTimeout(10_000) { pending.await() })
                assertFalse(TelecomEvidence.calls().isEmpty())
                engine.end(id)
                awaitTelecomRemoval()
            } finally {
                pending.cancelAndJoin()
                runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = previous; engine.actions.resetBridge()
            }
        }
    }

    @Test fun aCallEndedBeforeAdmissionDisconnectsItsLateCoreScope(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = ConversationEngine.get(context)
            val previous = engine.emit; val selection = engine.nativeMedia
            val id = "late-admission-${System.nanoTime()}"
            val baselineJobs = engine.jobs.coroutineContext[Job]!!.children.toSet()
            val endedBeforeAdmission = CompletableDeferred<Result<Unit>>()
            val terminal = CompletableDeferred<Unit>()
            val lateDisconnected = CompletableDeferred<Unit>()
            var admitted = false
            val observer = NativeMediaObserver { signal ->
                if (signal.serverCallId == id && signal.stage == NativeMediaStage.CORE_ADD_REQUESTED) {
                    engine.jobs.launch(start = CoroutineStart.UNDISPATCHED) {
                        endedBeforeAdmission.complete(runCatching {
                            engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                        })
                    }
                }
                if (signal.serverCallId == id && signal.stage == NativeMediaStage.CORE_ADMITTED) admitted = true
                if (signal.serverCallId == id && signal.stage == NativeMediaStage.CORE_LATE_DISCONNECTED) lateDisconnected.complete(Unit)
            }
            engine.nativeMedia = AndroidNativeMedia.NONE; engine.actions.resetBridge()
            engine.emit = { name, body ->
                if (name == "conversationsChanged" && (body["conversations"] as List<*>).any {
                    (it as Map<*, *>)["id"] == id && it["status"] == "ended"
                }) terminal.complete(Unit)
            }
            NativeMediaSignals.observers += observer
            try {
                val outcome = withTimeout(6000) { engine.incoming(mapOf("eventId" to id, "serverCallId" to id,
                    "caller" to mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), "hasVideo" to false)) }
                assertEquals("Early incoming: $outcome", "failed", outcome["status"])
                assertEquals("Early incoming: $outcome", "conversation", (outcome["error"] as Map<*, *>)["code"])
                try {
                    withTimeout(6000) {
                        endedBeforeAdmission.await().getOrThrow(); terminal.await(); lateDisconnected.await()
                        while (engine.jobs.coroutineContext[Job]!!.children.any { it !in baselineJobs && it.isActive } ||
                            TelecomEvidence.calls().isNotEmpty()) delay(10)
                    }
                } catch (error: TimeoutCancellationException) {
                    throw AssertionError("Late cleanup: end=${endedBeforeAdmission.isCompleted}, terminal=${terminal.isCompleted}, disconnect=${lateDisconnected.isCompleted}", error)
                }
                assertFalse(admitted)
                assertFalse((engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id })
            } finally {
                NativeMediaSignals.observers -= observer
                runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = previous; engine.nativeMedia = selection; engine.actions.resetBridge()
            }
        }
    }

    @Test fun collidingRawIdsHaveIndependentNotificationActions(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = ConversationEngine.get(context)
            val previous = engine.emit; val selection = engine.nativeMedia
            val prefix = "notification-${System.nanoTime()}-"
            val first = prefix + "Aa"; val second = prefix + "BB"
            assertEquals(first.hashCode(), second.hashCode())
            engine.nativeMedia = AndroidNativeMedia.NONE; engine.actions.resetBridge()
            val performed = mutableListOf<Map<*, *>>()
            engine.emit = { name, body -> if (name == "conversationEvent" && body["type"] == "actionPerformed") performed += body["payload"] as Map<*, *> }
            fun invitation(id: String) = mapOf("eventId" to id, "serverCallId" to id,
                "caller" to mapOf("handleType" to "generic", "value" to id, "displayName" to id), "hasVideo" to false)
            fun present(id: String) = (engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }
            try {
                assertEquals(first, engine.incoming(invitation(first))["id"])
                engine.join(first); engine.hold(first, true)
                assertEquals(second, engine.incoming(invitation(second))["id"])
                suspend fun action(id: String): android.app.PendingIntent = withTimeout(5000) {
                    var found: android.app.PendingIntent? = null
                    while (found == null) {
                        found = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull {
                            it.notification.extras.getParcelable(android.app.Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)?.name?.toString() == id
                        }?.notification?.actions?.firstOrNull()?.actionIntent
                        if (found == null) delay(10)
                    }
                    checkNotNull(found)
                }
                val firstAction = action(first); val secondAction = action(second)
                assertNotEquals(firstAction, secondAction)
                firstAction.send()
                withTimeout(5000) { while (present(first)) delay(10) }
                assertTrue(present(second))
                assertTrue(performed.any { it["conversationId"] == first && it["origin"] == "system" && it["action"] == mapOf("kind" to "end") })
                val ending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.end(second) } }
                assertFalse("Must exercise an outstanding native disconnect", ending.isCompleted)
                runCatching { engine.report(second, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                withTimeout(5000) { ending.await(); while (present(second) || TelecomEvidence.calls().isNotEmpty()) delay(10) }
                assertFalse(performed.any { it["conversationId"] == second && it["action"] == mapOf("kind" to "end") })
            } finally {
                for (id in listOf(first, second)) runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                engine.emit = previous; engine.nativeMedia = selection; engine.actions.resetBridge()
            }
        }
    }

}
