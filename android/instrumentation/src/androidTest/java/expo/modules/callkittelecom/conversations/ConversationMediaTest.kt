package expo.modules.callkittelecom.conversations

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationMediaTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = ConversationEngine.get(context)
        val id = "provided-state-${System.nanoTime()}"
        val grant = mapOf("provider" to "livekit", "expiresAt" to System.currentTimeMillis() / 1000 + 120, "token" to "private-fixture-grant")
        private val previousMedia = engine.nativeMedia; private val previousPolicy = engine.policy
        private val previousEmit = engine.emit; private val previousAccept = engine.actions.acceptUnhandled
        private val previousFactory = NativeMediaRegistry.factories["livekit"]
        private val previousHandler = ConversationActions.handler
        val sessions = CopyOnWriteArrayList<Session>()
        val signals = mutableListOf<NativeMediaSignal>()
        var preparationGate: CompletableDeferred<Unit>? = null
        var ended: Map<*, *>? = null
        private val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
        init {
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.policy = JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to false))
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            engine.emit = { name, body -> if (name == "conversationsChanged") {
                (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull {
                    it["id"] == id && it["status"] == "ended"
                }?.let { ended = it["ended"] as Map<*, *> }
            } }
            NativeMediaSignals.observers += observer
            NativeMediaRegistry.factories["livekit"] = { Session().also { sessions += it } }
        }
        inner class Session : NativeMediaSession {
            private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val room = MutableSharedFlow<NativeRoomState>()
            private var failure: () -> Unit = {}
            private var connection: NativeRoomConnection? = null
            val entered = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            var payload: Map<String, Any?>? = null
            var activations = 0
            var closes = 0
            @Volatile var muted = false
            var mutedAtActivation = false
            override fun setFailureListener(listener: () -> Unit) { failure = listener }
            override suspend fun prewarm(payload: Map<String, Any?>) {
                this.payload = payload; entered.complete(Unit); preparationGate?.await()
                connection = NativeRoomConnection(scope, { NativeMediaSignals.emit(id, it) }, { failure() }).also { it.observe(room) }
                NativeMediaSignals.emit(id, NativeMediaStage.ROOM_CONNECTED)
            }
            override suspend fun activate() { mutedAtActivation = muted; activations++ }
            override suspend fun setHeld(held: Boolean) {}
            override suspend fun setMuted(muted: Boolean) { this.muted = muted }
            override suspend fun close(reason: String) { closes++; connection?.close(); scope.cancel(); closed.complete(Unit) }
        }
        fun state() = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
        suspend fun stage(kind: NativeMediaStage, count: Int = 1) = withTimeout(5000) {
            while (signals.count { it.stage == kind } < count) delay(10)
        }
        suspend fun start() { assertEquals(id, engine.start(mapOf("id" to id, "to" to mapOf("id" to "peer")))) }
        suspend fun removed() = withTimeout(5000) { while (state() != null || TelecomEvidence.calls().isNotEmpty()) delay(10) }
        suspend fun close() {
            try {
                if (state() != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                removed()
                withTimeout(5000) { sessions.forEach { it.closed.await() } }
            } finally {
                preparationGate?.complete(Unit)
                NativeMediaSignals.observers -= observer
                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit") else NativeMediaRegistry.factories["livekit"] = previousFactory
                engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                engine.actions.resetBridge(); engine.actions.acceptUnhandled = previousAccept; engine.emit = previousEmit
                ConversationActions.configure(previousHandler)
            }
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) { val fixture = Fixture(); try { fixture.body() } finally { fixture.close() } }
    }

    @Test fun incomingCredentialsRemainPrivateUntilAnswer() = fixture {
        val incoming = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"))
        assertEquals("reported", engine.incoming(incoming.payload())["status"])
        ConversationMedia.provide(context, id, grant)
        stage(NativeMediaStage.MEDIA_PROVIDED)
        assertTrue(sessions.isEmpty())
        assertFalse(engine.snapshot.toString().contains(grant["token"] as String))
        engine.mute(id, true)
        engine.join(id); stage(NativeMediaStage.CONNECTED)
        assertEquals(grant, sessions.single().payload?.get("media"))
        assertTrue(sessions.single().mutedAtActivation)
        assertFalse(engine.snapshot.toString().contains(grant["token"] as String))
        assertFalse(signals.toString().contains(grant["token"] as String))
    }

    @Test fun decliningBeforeAnswerDiscardsCredentialsWithoutCreatingASession() = fixture {
        val incoming = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"))
        assertEquals("reported", engine.incoming(incoming.payload())["status"])
        ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.MEDIA_PROVIDED)
        engine.end(id); removed()
        ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.MEDIA_IGNORED)
        assertEquals("unknownConversation", signals.last { it.stage == NativeMediaStage.MEDIA_IGNORED }.reason)
        assertTrue(sessions.isEmpty())
    }

    @Test fun endingPlacementWhileCredentialsPrepareCannotActivateOrLeaveASession() = fixture {
        preparationGate = CompletableDeferred()
        engine.policy.put("prewarm", true)
        val placed = CompletableDeferred<Unit>()
        val previous = engine.emit
        engine.emit = { name, body -> if (name == "handlerAction") placed.complete(Unit) else previous(name, body) }
        engine.actions.attach(true, 1, engine.actions.epoch)
        coroutineScope {
            val starting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { start() } }
            withTimeout(1000) { placed.await() }
            ConversationMedia.provide(context, id, grant)
            withTimeout(1000) { while (sessions.isEmpty()) delay(10); sessions.single().entered.await() }
            engine.end(id)
            assertTrue(starting.await().isFailure)
        }
        removed()
        val session = sessions.single()
        withTimeout(3000) { session.closed.await() }
        preparationGate?.complete(Unit)
        assertEquals(0, session.activations); assertEquals(1, session.closes)
        assertFalse(signals.any { it.stage == NativeMediaStage.CONNECTED })
    }

    @Test fun roomRecoveryPreservesConnectedAndHeldStatusUntilFinalDisconnection() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        val session = sessions.single()
        session.room.emit(NativeRoomState.RECONNECTING); stage(NativeMediaStage.RECONNECTING)
        assertEquals("connected", state()?.get("status")); assertNull(ended)
        session.room.emit(NativeRoomState.RECONNECTED); stage(NativeMediaStage.RECONNECTED)
        assertEquals("connected", state()?.get("status")); assertEquals(1, session.activations)
        engine.hold(id, true)
        session.room.emit(NativeRoomState.RECONNECTING); stage(NativeMediaStage.RECONNECTING, 2)
        assertEquals("held", state()?.get("status")); assertNull(ended)
        session.room.emit(NativeRoomState.RECONNECTED); stage(NativeMediaStage.RECONNECTED, 2)
        assertEquals("held", state()?.get("status"))
        session.room.emit(NativeRoomState.DISCONNECTED)
        removed()
        assertEquals("failed", ended?.get("reason"))
        assertEquals("media", (ended?.get("failure") as Map<*, *>)["kind"])
        withTimeout(3000) { session.closed.await() }
        assertEquals(1, session.closes)
    }

    @Test fun credentialsSuppliedToAHeldMutedOutgoingCallWaitForResumeAndKeepMute() = fixture {
        start(); engine.hold(id, true); engine.mute(id, true)
        ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.MEDIA_PROVIDED)
        assertEquals("held", state()?.get("status"))
        assertTrue(sessions.all { it.activations == 0 })
        engine.hold(id, false); stage(NativeMediaStage.CONNECTED)
        assertEquals("connected", state()?.get("status"))
        assertEquals(true, state()?.get("isMuted"))
        assertTrue(sessions.single().mutedAtActivation)
    }

    @Test fun aRoomThatFinallyDisconnectsBeforeFirstActivationStillEndsTheHeldCall() = fixture {
        engine.policy.put("prewarm", true)
        start(); engine.hold(id, true)
        ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.ROOM_CONNECTED)
        val session = sessions.single()
        assertEquals(0, session.activations); assertEquals("held", state()?.get("status"))
        session.room.emit(NativeRoomState.DISCONNECTED)
        removed()
        assertEquals("failed", ended?.get("reason"))
        withTimeout(3000) { session.closed.await() }
        assertEquals(1, session.closes)
    }

    @Test fun aRefusedConcurrentHoldCannotStrandProvidedMedia() = fixture {
        ConversationActions.configure { action ->
            if (action is ConversationAction.Hold && action.held && action.origin == "app") ConversationActionResult.Refuse("keepActive")
            else ConversationActionResult.Proceed()
        }
        start()
        ConversationMedia.provide(context, id, grant)
        val error = runCatching { engine.hold(id, true) }.exceptionOrNull() as Exception
        assertEquals("handler_failed", CommandRejection.from(error).code)
        stage(NativeMediaStage.CONNECTED)
        assertEquals("connected", state()?.get("status"))
        assertEquals(1, sessions.single().activations)
    }
}
