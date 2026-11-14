package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.os.SystemClock
import androidx.core.telecom.CallControlScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationActionsJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private class Fixture {
        val engine = ConversationEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
        private val previousPolicy = engine.policy
        private val previousMedia = engine.nativeMedia
        private val previousEmit = engine.emit
        private val previousFactory = NativeMediaRegistry.factories["livekit"]
        private val ids = mutableListOf<String>()
        val actions = CopyOnWriteArrayList<ConversationAction>()
        val prepared = CopyOnWriteArrayList<Map<String, Any?>>()
        val signals = mutableListOf<NativeMediaSignal>()
        val ended = mutableMapOf<String, String>()
        val performed = mutableListOf<Map<*, *>>()
        @Volatile var muteGate: CompletableDeferred<Unit>? = null
        val muteEntered = CompletableDeferred<Unit>()
        @Volatile var mediaMuted = false
        @Volatile var failMediaMute = false
        private val observer = NativeMediaObserver { signals += it }
        init {
            engine.actions.resetBridge()
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.emit = { name, body ->
                assertNotEquals("handlerAction", name)
                if (name == "conversationsChanged") for (state in body["conversations"] as List<*>) {
                    val value = state as Map<*, *>
                    if (value["status"] == "ended") ended[value["id"] as String] = (value["ended"] as Map<*, *>)["reason"] as String
                }
                if (name == "conversationEvent" && body["type"] == "actionPerformed") performed += body["payload"] as Map<*, *>
            }
            NativeMediaSignals.observers += observer
            NativeMediaRegistry.factories["livekit"] = {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) {
                        prepared += payload
                        NativeMediaSignals.emit(payload["serverCallId"] as String, NativeMediaStage.ROOM_CONNECTED)
                    }
                    override suspend fun activate() {}
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(muted: Boolean) {
                        if (muted && failMediaMute) error("Capture session lost")
                        if (muted) { muteEntered.complete(Unit); muteGate?.await() }
                        mediaMuted = muted
                    }
                    override suspend fun close(reason: String) {}
                }
            }
        }
        fun handler(body: suspend (ConversationAction) -> ConversationActionResult) {
            ConversationActions.configure { action -> actions += action; body(action) }
        }
        fun grant(tag: String) = mapOf("provider" to "livekit", "expiresAt" to System.currentTimeMillis() / 1000 + 120, "token" to tag)
        suspend fun incoming(prewarm: Boolean = false, media: Map<String, Any?>? = null, id: String = "server-actions-${System.nanoTime()}"): String {
            engine.policy = JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to prewarm))
            ids += id
            val push = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "caller", "displayName" to id), media = media)
            assertEquals("reported", engine.incoming(push.payload())["status"])
            return id
        }
        fun state(id: String) = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
        suspend fun connected(id: String) = withTimeout(5000) { while (state(id)?.get("status") != "connected") delay(10) }
        suspend fun notificationAnswer(id: String) {
            val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(NotificationManager::class.java)
            val answer = withTimeout(5000) {
                var pending: PendingIntent? = null
                while (pending == null) {
                    val notification = manager.activeNotifications.singleOrNull {
                        it.notification.category == Notification.CATEGORY_CALL &&
                            it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == id
                    }?.notification
                    pending = notification?.extras?.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
                    if (pending == null) delay(10)
                }
                checkNotNull(pending)
            }
            answer.send()
        }
        suspend fun removed(id: String) = withTimeout(5000) { while (state(id) != null || TelecomEvidence.calls().isNotEmpty()) delay(10) }
        suspend fun close() {
            ConversationActions.configure(null)
            muteGate?.complete(Unit)
            try { withContext(NonCancellable) {
                for (id in ids) if (state(id) != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                ids.lastOrNull()?.let { removed(it) }
            } } finally {
                engine.policy = previousPolicy; engine.nativeMedia = previousMedia; engine.emit = previousEmit
                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previousFactory
                NativeMediaSignals.observers -= observer
            }
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) { val fixture = Fixture(); try { fixture.body() } finally { fixture.close() } }
    }

    @Test fun mediaFailureDuringSystemMuteIsMediaFailureRatherThanFrameworkRemoval() = fixture {
        val originalAdmission = engine.coreAdmission
        val originalEmit = engine.emit
        val coreMuted = MutableSharedFlow<Boolean>(replay = 1)
        var failure: Map<*, *>? = null
        engine.coreAdmission = CoreCallAdmission { attributes, callbacks, admitted ->
            originalAdmission.add(attributes, callbacks) {
                admitted(object : CallControlScope by this { override val isMuted: Flow<Boolean> = coreMuted })
            }
        }
        engine.emit = { name, body ->
            originalEmit(name, body)
            if (name == "conversationsChanged") (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
                .firstOrNull { it["status"] == "ended" }?.let { failure = (it["ended"] as Map<*, *>)["failure"] as? Map<*, *> }
        }
        try {
            handler { ConversationActionResult.Proceed() }
            val id = incoming(media = grant("mute-failure"))
            engine.join(id); connected(id)
            coreMuted.emit(false)
            withTimeout(5000) { while (signals.none { it.stage == NativeMediaStage.CORE_MUTE_OBSERVED }) delay(10) }
            failMediaMute = true
            coreMuted.emit(true)
            removed(id)
            assertEquals("failed", ended[id])
            assertEquals(mapOf("kind" to "media"), failure)
        } finally { engine.coreAdmission = originalAdmission; engine.emit = originalEmit }
    }

    @Test fun systemOriginAnswerConnectsWithoutJavaScriptAndSignalsItsOrderedTiming() = fixture {
        val grant = grant("answer-grant")
        handler { ConversationActionResult.Proceed(grant) }
        val id = "server-actions-${System.nanoTime()}"
        coroutineScope {
            val answer = async(start = CoroutineStart.UNDISPATCHED) { notificationAnswer(id) }
            assertFalse("Answer dispatch must wait for this call's notification", answer.isCompleted)
            assertEquals(id, incoming(id = id))
            answer.await()
        }
        connected(id)
        withTimeout(1000) { while (signals.none { it.serverCallId == id && it.stage == NativeMediaStage.CONNECTED }) delay(10) }
        assertEquals(listOf(ConversationAction.Answer(id, id, "system")), actions.toList())
        assertEquals(grant, prepared.single()["media"])
        val stages = listOf(NativeMediaStage.ANSWER_REQUESTED, NativeMediaStage.HANDLER_STARTED,
            NativeMediaStage.HANDLER_RESPONDED, NativeMediaStage.ROOM_CONNECTED, NativeMediaStage.CONNECTED)
        val timing = signals.filter { it.serverCallId == id && it.stage in stages }
        assertEquals(stages, timing.map { it.stage })
        assertEquals("proceed", timing.single { it.stage == NativeMediaStage.HANDLER_RESPONDED }.reason)
        assertTrue(timing.zipWithNext().all { (a, b) -> a.elapsedRealtimeNanos <= b.elapsedRealtimeNanos })
        assertTrue(performed.any { it["conversationId"] == id && it["origin"] == "system" && (it["action"] as Map<*, *>)["kind"] == "answer" })
    }

    @Test fun refusedAppAndSystemAnswersEndWithTheGivenReasonAndNeverJoinMedia() = fixture {
        handler { ConversationActionResult.Refuse("claimLost", "answeredElsewhere") }
        for (origin in NativeActionOrigin.entries) {
            val id = incoming()
            val error = runCatching {
                if (origin == NativeActionOrigin.SYSTEM) engine.answerFromSystem(id) else engine.join(id)
            }.exceptionOrNull() as Exception
            assertEquals("refused", CommandRejection.from(error).code)
            removed(id)
            assertEquals("answeredElsewhere", ended[id])
            assertEquals(ConversationAction.Answer(id, id, origin.wireValue), actions.last())
        }
        assertTrue(prepared.isEmpty()); assertTrue(performed.isEmpty())
    }

    @Test fun slowDeclineAndEndDisconnectWithinThreeSecondsAndNeverRetryLateResults() = fixture {
        val gate = CompletableDeferred<Unit>()
        try {
            for (answered in listOf(false, true)) {
                handler { ConversationActionResult.Proceed(grant("initial")) }
                val id = incoming()
                if (answered) { engine.join(id); connected(id) }
                val finished = CompletableDeferred<Unit>()
                handler {
                    withContext(NonCancellable) { gate.await() }
                    finished.complete(Unit)
                    ConversationActionResult.Refuse("lateFailure")
                }
                val began = SystemClock.elapsedRealtime()
                withTimeout(3000) { engine.end(id); removed(id) }
                assertTrue(SystemClock.elapsedRealtime() - began < 3000)
                assertEquals("local", ended[id])
                assertEquals(if (answered) ConversationAction.End(id, id, "app") else ConversationAction.Decline(id, id, "app"), actions.last())
                assertFalse(finished.isCompleted)
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun appAnswerAndConcurrentSystemEchoShareOneHandlerDecisionAndOneEvent() = fixture {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val id = incoming()
        handler { entered.complete(Unit); gate.await(); ConversationActionResult.Proceed(grant("shared")) }
        coroutineScope {
            val app = async(start = CoroutineStart.UNDISPATCHED) { engine.join(id) }
            withTimeout(1000) { entered.await() }
            // Dispatch the same callback entry point Core uses while the app request owns its receipt.
            val echo = async(start = CoroutineStart.UNDISPATCHED) { engine.answerFromSystem(id) }
            assertFalse(echo.isCompleted)
            gate.complete(Unit)
            withTimeout(5000) { app.await(); echo.await() }
        }
        connected(id)
        assertEquals(listOf(ConversationAction.Answer(id, id, "app")), actions.toList())
        withTimeout(1000) { while (signals.none { it.serverCallId == id && it.stage == NativeMediaStage.CONNECTED }) delay(10) }
        assertEquals(1, signals.count { it.serverCallId == id && it.stage == NativeMediaStage.ANSWER_REQUESTED })
        assertEquals(1, performed.count { it["conversationId"] == id && (it["action"] as Map<*, *>)["kind"] == "answer" })
    }

    @Test fun appControlsCanBeRefusedWhileSystemObservationsKeepTheirState() = fixture {
        handler { ConversationActionResult.Proceed(grant("initial")) }
        val id = incoming(); engine.join(id); connected(id)
        handler { ConversationActionResult.Refuse("serverUnavailable") }
        for (control in listOf<suspend () -> Unit>({ engine.hold(id, true) }, { engine.mute(id, true) })) {
            val error = runCatching { control() }.exceptionOrNull() as Exception
            assertEquals("refused", CommandRejection.from(error).code)
        }
        assertEquals("connected", state(id)?.get("status")); assertEquals(false, state(id)?.get("isMuted"))
        engine.hold(id, true, NativeActionOrigin.SYSTEM)
        assertEquals("held", state(id)?.get("status"))
        engine.mute(id, true, NativeActionOrigin.SYSTEM)
        assertEquals(true, state(id)?.get("isMuted"))
        assertEquals(ConversationAction.Mute(id, id, "system", true), actions.last())
    }

    @Test fun aSharedAppRefusalCannotVetoConcurrentSystemHoldOrMute() = fixture {
        handler { ConversationActionResult.Proceed(grant("initial")) }
        val id = incoming(); engine.join(id); connected(id)
        for (hold in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            handler { entered.complete(Unit); gate.await(); ConversationActionResult.Refuse("serverUnavailable") }
            coroutineScope {
                val app = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
                    if (hold) engine.hold(id, true) else engine.mute(id, true)
                } }
                withTimeout(1000) { entered.await() }
                val system = async(start = CoroutineStart.UNDISPATCHED) {
                    if (hold) engine.hold(id, true, NativeActionOrigin.SYSTEM) else engine.mute(id, true, NativeActionOrigin.SYSTEM)
                }
                gate.complete(Unit)
                assertEquals("refused", CommandRejection.from(app.await().exceptionOrNull() as Exception).code)
                withTimeout(1000) { system.await() }
            }
            assertEquals(if (hold) "held" else true, state(id)?.get(if (hold) "status" else "isMuted"))
            val kind = if (hold) "hold" else "mute"
            assertEquals("system", performed.last { (it["action"] as Map<*, *>)["kind"] == kind }["origin"])
            assertEquals(1, actions.count { if (hold) it is ConversationAction.Hold else it is ConversationAction.Mute })
        }
    }

    @Test fun oppositeSystemControlsSupersedeAHandlerStillWaitingOnTheEarlierState() = fixture {
        handler { ConversationActionResult.Proceed(grant("initial")) }
        val id = incoming(); engine.join(id); connected(id)
        for (hold in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            handler { action ->
                val value = when (action) { is ConversationAction.Hold -> action.held; is ConversationAction.Mute -> action.muted; else -> false }
                if (value) { entered.complete(Unit); gate.await() }
                ConversationActionResult.Proceed()
            }
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    if (hold) engine.hold(id, true, NativeActionOrigin.SYSTEM) else engine.mute(id, true, NativeActionOrigin.SYSTEM)
                }
                withTimeout(1000) { entered.await() }
                if (hold) engine.hold(id, false, NativeActionOrigin.SYSTEM) else engine.mute(id, false, NativeActionOrigin.SYSTEM)
                gate.complete(Unit); withTimeout(1000) { first.await() }
            }
            assertEquals(if (hold) "connected" else false, state(id)?.get(if (hold) "status" else "isMuted"))
            val kind = if (hold) "hold" else "mute"
            assertEquals(1, performed.count { (it["action"] as Map<*, *>)["kind"] == kind })
        }
    }

    @Test fun endOwnsPendingOutgoingPlacementBeforeTheNativeHandlerSuspends() = fixture {
        val id = "pending-server-actions-${System.nanoTime()}"
        val placement = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val original = engine.emit
        engine.emit = { name, body -> if (name == "handlerAction") placement.complete(Unit) else original(name, body) }
        engine.actions.attach(true, 1, engine.actions.epoch)
        handler { entered.complete(Unit); gate.await(); ConversationActionResult.Refuse("ignoredEndFailure") }
        try { coroutineScope {
            val start = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.start(mapOf("id" to id, "to" to mapOf("id" to "callee"))) } }
            withTimeout(1000) { placement.await() }
            val end = async(start = CoroutineStart.UNDISPATCHED) { engine.end(id) }
            withTimeout(1000) { entered.await() }
            assertTrue(start.await().isFailure)
            assertNotNull(state(id)); assertNull(ended[id])
            gate.complete(Unit); withTimeout(1000) { end.await() }
            assertEquals("local", ended[id]); removed(id)
            assertEquals(listOf(ConversationAction.End(id, null, "app")), actions.toList())
            assertEquals(1, performed.count { it["conversationId"] == id && (it["action"] as Map<*, *>)["kind"] == "end" })
        } } finally { gate.complete(Unit); engine.emit = original }
    }

    @Test fun aLocalDeclineCancelsTheRingingDeadlineBeforeWaitingOnTheHandler() = fixture {
        val previous = engine.incomingRingDeadline
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        try {
            engine.incomingRingDeadline = IncomingRingDeadline(waitMillis = {
                try { awaitCancellation() } finally { cancelled.complete(!currentCoroutineContext().isActive) }
            })
            val id = incoming()
            handler { entered.complete(Unit); gate.await(); ConversationActionResult.Proceed() }
            coroutineScope {
                val end = async(start = CoroutineStart.UNDISPATCHED) { engine.end(id) }
                withTimeout(1000) { entered.await() }
                assertTrue(withTimeout(1000) { cancelled.await() })
                assertNotNull(state(id)); assertNull(ended[id])
                gate.complete(Unit); withTimeout(1000) { end.await() }
            }
            assertEquals("local", ended[id]); removed(id)
        } finally { gate.complete(Unit); engine.incomingRingDeadline = previous }
    }

    @Test fun aNewSystemUnmuteSupersedesMuteStillSuspendedInTheMediaSession() = fixture {
        handler { ConversationActionResult.Proceed(grant("initial")) }
        val id = incoming(); engine.join(id); connected(id)
        val gate = CompletableDeferred<Unit>()
        muteGate = gate
        try { coroutineScope {
            val mute = async(start = CoroutineStart.UNDISPATCHED) { engine.mute(id, true, NativeActionOrigin.SYSTEM) }
            withTimeout(1000) { muteEntered.await() }
            engine.mute(id, false, NativeActionOrigin.SYSTEM)
            gate.complete(Unit); withTimeout(1000) { mute.await() }
            withTimeout(1000) { while (mediaMuted) delay(10) }
            assertEquals(false, state(id)?.get("isMuted"))
            assertEquals(1, performed.count { (it["action"] as Map<*, *>)["kind"] == "mute" })
        } } finally { gate.complete(Unit) }
    }

    @Test fun aRepeatedMuteAfterAnOppositeControlGetsANewReceipt() = fixture {
        handler { ConversationActionResult.Proceed(grant("initial")) }
        val id = incoming(); engine.join(id); connected(id)
        val gate = CompletableDeferred<Unit>()
        muteGate = gate
        try { coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { engine.mute(id, true, NativeActionOrigin.SYSTEM) }
            withTimeout(1000) { muteEntered.await() }
            engine.mute(id, false, NativeActionOrigin.SYSTEM)
            val last = async(start = CoroutineStart.UNDISPATCHED) { engine.mute(id, true, NativeActionOrigin.SYSTEM) }
            gate.complete(Unit); withTimeout(2000) { first.await(); last.await() }
            assertEquals(true, state(id)?.get("isMuted"))
            assertEquals(3, actions.count { it is ConversationAction.Mute })
            assertEquals(2, performed.count { (it["action"] as Map<*, *>)["kind"] == "mute" })
        } } finally { gate.complete(Unit) }
    }

    @Test fun aSystemMuteEchoDuringMediaSuspensionKeepsTheSharedEffect() = fixture {
        handler { ConversationActionResult.Proceed(grant("initial")) }
        val id = incoming(); engine.join(id); connected(id)
        val gate = CompletableDeferred<Unit>()
        muteGate = gate
        try { coroutineScope {
            val app = async(start = CoroutineStart.UNDISPATCHED) { engine.mute(id, true) }
            withTimeout(1000) { muteEntered.await() }
            engine.mute(id, true, NativeActionOrigin.SYSTEM)
            gate.complete(Unit); withTimeout(2000) { app.await() }
            assertEquals(true, state(id)?.get("isMuted"))
            assertEquals(1, actions.count { it is ConversationAction.Mute })
            assertEquals(1, performed.count { (it["action"] as Map<*, *>)["kind"] == "mute" })
        } } finally { gate.complete(Unit) }
    }
}
