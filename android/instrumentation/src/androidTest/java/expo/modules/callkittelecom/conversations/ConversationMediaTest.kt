package expo.modules.callkittelecom.conversations

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import io.livekit.android.events.DisconnectReason
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
        val performed = mutableListOf<Map<*, *>>()
        var preparationGate: CompletableDeferred<Unit>? = null
        var roomConnectGate: CompletableDeferred<Unit>? = null
        var ended: Map<*, *>? = null
        var terminalSnapshots = 0
        private val observer = NativeMediaObserver { if (it.serverCallId == id || it.stage == NativeMediaStage.ROOM_DISCONNECTED) signals += it }
        init {
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.policy = JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to false))
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            engine.emit = { name, body ->
                if (name == "conversationEvent" && body["type"] == "actionPerformed") performed += body["payload"] as Map<*, *>
                if (name == "conversationsChanged") {
                (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull {
                    it["id"] == id && it["status"] == "ended"
                }?.let { ended = it["ended"] as Map<*, *>; terminalSnapshots++ }
            } }
            NativeMediaSignals.observers += observer
            NativeMediaRegistry.factories["livekit"] = { Session().also { sessions += it } }
        }
        inner class Session : NativeMediaSession {
            private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val room = MutableSharedFlow<NativeRoomState>()
            private var failure: () -> Unit = {}
            private var disconnection: (NativeMediaDisconnection) -> Unit = {}
            private var connection: NativeRoomConnection? = null
            val entered = CompletableDeferred<Unit>()
            val observing = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            var payload: Map<String, Any?>? = null
            var activations = 0
            var closes = 0
            @Volatile private var roomLive = false
            override val isPrepared get() = roomLive
            @Volatile var muted = false
            var mutedAtActivation = false
            val tones = CopyOnWriteArrayList<String>()
            val toneEntered = CompletableDeferred<Unit>()
            var toneGate: CompletableDeferred<Unit>? = null
            var failTones = false
            @Volatile var held = false
            override fun setFailureListener(listener: () -> Unit) { failure = listener }
            override fun setDisconnectListener(listener: (NativeMediaDisconnection) -> Unit) { disconnection = listener }
            fun disconnect() { roomLive = false; failure() }
            override suspend fun prewarm(payload: Map<String, Any?>) {
                this.payload = payload; entered.complete(Unit); preparationGate?.await()
                roomLive = true
                connection = NativeRoomConnection(scope, { stage, reason -> NativeMediaSignals.emit(id, stage, reason = reason) }, { reason ->
                    roomLive = false; disconnection(reason?.disconnection() ?: NativeMediaDisconnection.FAILURE)
                }).also { it.observe(room) }
                observing.complete(Unit); roomConnectGate?.await()
                NativeMediaSignals.emit(id, NativeMediaStage.ROOM_CONNECTED)
            }
            override suspend fun activate() { mutedAtActivation = muted; activations++ }
            override suspend fun setHeld(held: Boolean) { this.held = held }
            override suspend fun setMuted(muted: Boolean) { this.muted = muted }
            override suspend fun playTones(digits: String) {
                toneEntered.complete(Unit); toneGate?.await()
                check(!failTones) { "DTMF publication failed" }
                tones += digits
            }
            override suspend fun close(reason: String) { closes++; connection?.close(DisconnectReason.CLIENT_INITIATED.takeIf { roomLive }); scope.cancel(); closed.complete(Unit) }
        }
        fun state() = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
        suspend fun stage(kind: NativeMediaStage, count: Int = 1) = withTimeout(5000) {
            while (signals.count { it.stage == kind } < count) delay(10)
        }
        suspend fun start() { assertEquals(id, engine.start(mapOf("id" to id, "to" to mapOf("id" to "peer")))) }
        suspend fun removed() = withTimeout(5000) { while (state() != null || TelecomEvidence.calls().isNotEmpty()) delay(10) }
        suspend fun close() {
            try {
                sessions.forEach { it.toneGate?.complete(Unit) }
                if (state() != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                removed()
                withTimeout(5000) { sessions.forEach { it.closed.await() } }
            } finally {
                preparationGate?.complete(Unit); roomConnectGate?.complete(Unit)
                NativeMediaSignals.observers -= observer
                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit") else NativeMediaRegistry.factories["livekit"] = previousFactory
                engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                engine.actions.resetBridge(); engine.actions.acceptUnhandled = previousAccept; engine.emit = previousEmit
                ConversationActions.configure(previousHandler)
            }
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            // A preceding suite's disconnected Telecom call can outlive its engine snapshot.
            // Establish isolation before installing this fixture's global media factory.
            withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(10) }
            val fixture = Fixture()
            try { fixture.body() } finally { fixture.close() }
        }
    }

    private enum class AnswerCredentials { PUSH, HANDLER, PROVIDED }

    @Test fun tonesReachTheSessionAndNativeControlsPublishOneReceiptPerPress() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        assertEquals(true, (state()?.get("capabilities") as Map<*, *>)["tones"])
        ConversationActions.configure { error("Tones must not ask the server action handler") }
        engine.tones(id, "12*#abCD")
        val session = sessions.single()
        assertEquals(listOf("1", "2", "*", "#", "A", "B", "C", "D"), session.tones.toList())
        assertEquals(1, performed.count { it["action"] == mapOf("kind" to "tones", "digits" to "12*#abCD") })
        engine.tones(id, "1"); engine.tones(id, "1")
        ConversationControls.playTones(context, id, "9")
        withTimeout(3000) { while (performed.count { (it["action"] as Map<*, *>)["kind"] == "tones" } != 4) delay(10) }
        assertEquals(listOf("1", "1", "9"), session.tones.takeLast(3))
        assertTrue(performed.filter { (it["action"] as Map<*, *>)["kind"] == "tones" }.all { it["origin"] == "app" })
    }

    @Test fun invalidHeldAndFailedTonesHaveNoPerformedReceipt() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        val session = sessions.single()
        for (digits in listOf("", "12x")) assertEquals("invalid_arguments", CommandRejection.from(
            runCatching { engine.tones(id, digits) }.exceptionOrNull() as Exception).code)
        assertTrue(session.tones.isEmpty())
        session.failTones = true
        assertTrue(runCatching { engine.tones(id, "1") }.isFailure)
        assertEquals("connected", state()?.get("status"))
        engine.hold(id, true)
        assertEquals("invalid_arguments", CommandRejection.from(
            runCatching { engine.tones(id, "1") }.exceptionOrNull() as Exception).code)
        assertFalse(performed.any { (it["action"] as Map<*, *>)["kind"] == "tones" })
    }

    @Test fun tonesAreUnsupportedWithoutNativeLiveKitAndRequireReadyMediaWhenSelected() = fixture {
        engine.nativeMedia = AndroidNativeMedia.NONE
        start()
        assertEquals(false, (state()?.get("capabilities") as Map<*, *>)["tones"])
        assertEquals("unsupported", CommandRejection.from(
            runCatching { engine.tones(id, "1") }.exceptionOrNull() as Exception).code)
        assertTrue(sessions.isEmpty())
    }

    @Test fun tonesCannotCreateAnUnpreparedMediaSession() = fixture {
        start()
        assertEquals(true, (state()?.get("capabilities") as Map<*, *>)["tones"])
        assertEquals("invalid_arguments", CommandRejection.from(
            runCatching { engine.tones(id, "1") }.exceptionOrNull() as Exception).code)
        assertTrue(sessions.isEmpty())
        assertFalse(performed.any { (it["action"] as Map<*, *>)["kind"] == "tones" })
    }

    @Test fun endingTheCallCancelsOutstandingTonesWithoutAPerformedReceipt() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        val session = sessions.single().apply { toneGate = CompletableDeferred() }
        coroutineScope {
            val tones = async { runCatching { engine.tones(id, "12") } }
            withTimeout(3000) { session.toneEntered.await() }
            engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
            assertTrue(tones.await().isFailure)
        }
        removed()
        assertTrue(session.tones.isEmpty())
        assertFalse(performed.any { (it["action"] as Map<*, *>)["kind"] == "tones" })
    }

    @Test fun holdingDuringPublicationStopsRemainingDigitsAndSuppressesTheReceipt() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        val session = sessions.single().apply { toneGate = CompletableDeferred() }
        coroutineScope {
            val tones = async { runCatching { engine.tones(id, "12") } }
            withTimeout(3000) { session.toneEntered.await() }
            val holding = async { engine.hold(id, true) }
            withTimeout(3000) { while (state()?.get("status") != "held") delay(10) }
            withTimeout(1500) { holding.await() }
            assertFalse(session.toneGate!!.isCompleted)
            assertTrue(session.held)
            assertTrue(tones.await().isFailure)
        }
        assertTrue(session.tones.isEmpty())
        assertFalse(performed.any { (it["action"] as Map<*, *>)["kind"] == "tones" })
    }

    @Test fun mutingDuringPublicationDoesNotWaitForTheToneTransport() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        val session = sessions.single().apply { toneGate = CompletableDeferred() }
        coroutineScope {
            val tones = async { runCatching { engine.tones(id, "12") } }
            withTimeout(3000) { session.toneEntered.await() }
            withTimeout(1500) { engine.mute(id, true) }
            assertFalse(session.toneGate!!.isCompleted)
            assertTrue(session.muted)
            assertTrue(tones.await().isFailure)
        }
        assertTrue(session.tones.isEmpty())
        assertFalse(performed.any { (it["action"] as Map<*, *>)["kind"] == "tones" })
    }

    private suspend fun Fixture.answerAfterPrewarmLoss(source: AnswerCredentials) {
        engine.policy.put("prewarm", true)
        val incoming = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"))
        assertEquals("reported", engine.incoming(incoming.payload() + ("media" to grant))["status"])
        stage(NativeMediaStage.ROOM_CONNECTED)
        val old = sessions.single()
        old.room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.UNKNOWN_REASON))
        stage(NativeMediaStage.PREWARM_LOST)
        withTimeout(3000) { old.closed.await() }
        assertEquals("ringing", state()?.get("status")); assertNull(ended)
        assertTrue(TelecomEvidence.calls().single().contains("state=RINGING"))
        assertEquals(1, old.closes); assertEquals(0, old.activations)
        val replacement = grant + ("token" to "replacement-fixture-grant")
        when (source) {
            AnswerCredentials.PUSH -> Unit
            AnswerCredentials.HANDLER -> ConversationActions.configure { ConversationActionResult.Proceed(replacement) }
            AnswerCredentials.PROVIDED -> {
                ConversationMedia.provide(context, id, replacement)
                stage(NativeMediaStage.MEDIA_PROVIDED)
            }
        }
        engine.join(id); stage(NativeMediaStage.CONNECTED)
        assertEquals("connected", state()?.get("status"))
        assertEquals(2, sessions.size)
        assertEquals(if (source == AnswerCredentials.PUSH) grant else replacement, sessions.last().payload?.get("media"))
        assertEquals(1, sessions.last().activations)
        assertFalse(engine.snapshot.toString().contains("fixture-grant"))
        assertFalse(signals.toString().contains("fixture-grant"))
    }

    @Test fun lostRingingPrejoinUsesThePushCredentialsOnAnswer() = fixture { answerAfterPrewarmLoss(AnswerCredentials.PUSH) }
    @Test fun lostRingingPrejoinUsesTheAnswerHandlerCredentials() = fixture { answerAfterPrewarmLoss(AnswerCredentials.HANDLER) }
    @Test fun lostRingingPrejoinAcceptsProvidedCredentialsBeforeAnswer() = fixture { answerAfterPrewarmLoss(AnswerCredentials.PROVIDED) }

    @Test fun credentialsProvidedImmediatelyAfterPrejoinLossUseAFreshSession() = fixture {
        engine.policy.put("prewarm", true)
        val incoming = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), media = grant)
        assertEquals("reported", engine.incoming(incoming.payload())["status"])
        stage(NativeMediaStage.ROOM_CONNECTED)
        val replacement = grant + ("token" to "immediate-replacement-token")
        sessions.single().disconnect()
        // Main-thread room loss must be handled before the very next native command.
        ConversationMedia.provide(context, id, replacement)
        engine.join(id); stage(NativeMediaStage.CONNECTED)
        stage(NativeMediaStage.PREWARM_LOST); stage(NativeMediaStage.MEDIA_PROVIDED)
        assertFalse(signals.any { it.stage == NativeMediaStage.MEDIA_IGNORED })
        assertEquals(2, sessions.size)
        assertEquals(replacement, sessions.last().payload?.get("media"))
        assertEquals("connected", state()?.get("status")); assertNull(ended)
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
        session.room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.UNKNOWN_REASON))
        removed()
        assertEquals("failed", ended?.get("reason"))
        assertEquals("media", (ended?.get("failure") as Map<*, *>)["kind"])
        withTimeout(3000) { session.closed.await() }
        assertEquals(1, session.closes)
    }

    @Test fun answeredRoomDeletedEndsRemotelyAndRemovesTelecom() = fixture {
        val incoming = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), media = grant)
        assertEquals("reported", engine.incoming(incoming.payload())["status"])
        engine.join(id); stage(NativeMediaStage.CONNECTED)
        assertTrue(TelecomEvidence.calls().single().contains("state=ACTIVE"))
        val session = sessions.single()
        session.room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.ROOM_DELETED))
        removed()
        assertEquals("remoteEnded", ended?.get("reason"))
        assertNull(ended?.get("failure"))
        stage(NativeMediaStage.ROOM_DISCONNECTED)
        val signal = signals.single { it.stage == NativeMediaStage.ROOM_DISCONNECTED }
        assertEquals("ROOM_DELETED", signal.reason); assertEquals("", signal.serverCallId)
        assertTrue(signal.measurements.isEmpty())
        val snapshots = terminalSnapshots
        ConversationEnds.report(context, id, "remoteEnded")
        engine.remoteEnd(EndedCallPush(id, "$id-later-end", "remoteEnded").payload())
        yield()
        assertNull(state()); assertEquals(snapshots, terminalSnapshots)
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
        session.room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.UNKNOWN_REASON))
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

    @Test fun participantRemovalEndsAnActiveCallRemotely() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        sessions.single().room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.PARTICIPANT_REMOVED))
        removed()
        assertEquals("remoteEnded", ended?.get("reason")); assertNull(ended?.get("failure"))
        assertFalse(performed.any { (it["action"] as Map<*, *>)["kind"] == "end" })
    }

    @Test fun clientInitiatedRoomDisconnectDoesNotChangeLogicalState() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        sessions.single().room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.CLIENT_INITIATED))
        stage(NativeMediaStage.ROOM_DISCONNECTED)
        assertEquals("connected", state()?.get("status")); assertNull(ended)
        assertEquals("CLIENT_INITIATED", signals.single { it.stage == NativeMediaStage.ROOM_DISCONNECTED }.reason)
    }

    @Test fun localEndKeepsItsReasonAndSignalsTheLocalRoomReleaseOnce() = fixture {
        start(); ConversationMedia.provide(context, id, grant); stage(NativeMediaStage.CONNECTED)
        val session = sessions.single()
        engine.end(id); removed(); stage(NativeMediaStage.ROOM_DISCONNECTED)
        session.room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.CLIENT_INITIATED)); yield()
        assertEquals("local", ended?.get("reason")); assertNull(ended?.get("failure"))
        assertEquals(1, terminalSnapshots)
        assertEquals(listOf("CLIENT_INITIATED"), signals.filter { it.stage == NativeMediaStage.ROOM_DISCONNECTED }.map { it.reason })
    }

    private fun serverEndBeforeRoomDeletion(push: Boolean) = fixture {
        val incoming = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), media = grant)
        assertEquals("reported", engine.incoming(incoming.payload())["status"])
        engine.join(id); stage(NativeMediaStage.CONNECTED)
        val session = sessions.single()
        if (push) engine.remoteEnd(EndedCallPush(id, "$id-earlier-end", "remoteEnded").payload())
        else ConversationEnds.report(context, id, "remoteEnded")
        session.room.emit(NativeRoomState.DISCONNECTED(DisconnectReason.ROOM_DELETED))
        removed()
        assertEquals("remoteEnded", ended?.get("reason")); assertNull(ended?.get("failure"))
        assertEquals(1, terminalSnapshots)
        withTimeout(3000) { session.closed.await() }
        assertEquals(1, session.closes)
    }

    @Test fun earlierServerReportIsIdempotentWithRoomDeletion() = serverEndBeforeRoomDeletion(false)
    @Test fun earlierCallEndedPushIsIdempotentWithRoomDeletion() = serverEndBeforeRoomDeletion(true)

    @Test fun everyDisconnectReasonOnlyDiscardsTheRingingPrejoin() {
        for (reason in DisconnectReason.entries) fixture {
            engine.policy.put("prewarm", true)
            val incoming = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), media = grant)
            assertEquals("reported", engine.incoming(incoming.payload())["status"])
            stage(NativeMediaStage.ROOM_CONNECTED)
            sessions.single().room.emit(NativeRoomState.DISCONNECTED(reason))
            stage(NativeMediaStage.PREWARM_LOST); stage(NativeMediaStage.ROOM_DISCONNECTED)
            assertEquals(reason.name, "ringing", state()?.get("status")); assertNull(ended)
            assertTrue(TelecomEvidence.calls().single().contains("state=RINGING"))
            assertEquals(reason.name, signals.single { it.stage == NativeMediaStage.ROOM_DISCONNECTED }.reason)
            assertTrue(signals.filter { it.stage == NativeMediaStage.ROOM_DISCONNECTED }.all { it.serverCallId.isEmpty() })
        }
    }

    @Test fun remoteEndDuringRoomConnectEndsWithoutAMediaFailure() {
        for (reason in listOf(DisconnectReason.ROOM_DELETED, DisconnectReason.PARTICIPANT_REMOVED)) fixture {
            roomConnectGate = CompletableDeferred()
            start(); ConversationMedia.provide(context, id, grant)
            withTimeout(3000) { while (sessions.isEmpty()) delay(10); sessions.single().observing.await() }
            assertEquals("connecting", state()?.get("status"))
            assertEquals(0, sessions.single().activations)
            sessions.single().room.emit(NativeRoomState.DISCONNECTED(reason))
            removed()
            assertEquals(reason.name, "remoteEnded", ended?.get("reason")); assertNull(ended?.get("failure"))
            assertEquals(1, terminalSnapshots)
            stage(NativeMediaStage.ROOM_DISCONNECTED)
            assertEquals(reason.name, signals.single { it.stage == NativeMediaStage.ROOM_DISCONNECTED }.reason)
        }
    }
}
