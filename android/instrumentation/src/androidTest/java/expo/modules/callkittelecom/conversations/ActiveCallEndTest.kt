package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.os.SystemClock
import android.telecom.DisconnectCause
import androidx.core.telecom.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Core-Telecom lifetimes, with only room transport and disconnect failures substituted. */
@RunWith(AndroidJUnit4::class)
class ActiveCallEndTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = ConversationEngine.get(context)
        val id = "active-end-${System.nanoTime()}"
        private val previousAdmission = engine.coreAdmission
        private val previousMedia = engine.nativeMedia
        private val previousPolicy = engine.policy
        private val previousEmit = engine.emit
        private val previousAccept = engine.actions.acceptUnhandled
        private val previousHandler = ConversationActions.handler
        private val previousFactory = NativeMediaRegistry.factories["livekit"]
        private val previousRemovalDeadline = engine.platformRemovalDeadlineMillis
        private var realControls: CallControlScope? = null
        val coreClosed = CompletableDeferred<Unit>()
        var disconnects = 0
        var disconnectFailures = 0
        var throwDisconnectFailures = false
        var reconnectTimeoutMillis = NATIVE_MEDIA_RECONNECT_TIMEOUT_MILLIS
        var terminal: Map<*, *>? = null
        var terminalSnapshots = 0
        val events = mutableListOf<Map<String, Any?>>()
        val signals = mutableListOf<NativeMediaSignal>()
        private val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
        private var notificationId: Int? = null
        lateinit var session: Session

        init {
            NativeMediaSignals.observers += observer
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            ConversationActions.configure(null)
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.policy = JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to false))
            engine.coreAdmission = CoreCallAdmission { attributes, callbacks, admitted ->
                try {
                    previousAdmission.add(attributes, callbacks) {
                        val real = this
                        realControls = real
                        admitted(object : CallControlScope by real {
                            override suspend fun disconnect(cause: DisconnectCause): CallControlResult {
                                disconnects++
                                if (disconnectFailures > 0) {
                                    disconnectFailures--
                                    if (throwDisconnectFailures) error("Injected Core disconnect failure")
                                    return CallControlResult.Error(CallException.ERROR_UNKNOWN)
                                }
                                return real.disconnect(cause)
                            }
                        })
                    }
                } finally { coreClosed.complete(Unit) }
            }
            NativeMediaRegistry.factories["livekit"] = { Session().also { session = it } }
            engine.emit = { name, body ->
                if (name == "conversationEvent") events += body
                if (name == "conversationsChanged") {
                    (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull {
                        it["id"] == id && it["status"] == "ended"
                    }?.let { terminal = it["ended"] as Map<*, *>; terminalSnapshots++ }
                }
            }
        }

        inner class Session : NativeMediaSession {
            private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val room = MutableSharedFlow<NativeRoomState>()
            val closed = CompletableDeferred<Unit>()
            private var connection: NativeRoomConnection? = null
            private var failed: () -> Unit = {}
            override var isPrepared = false
                private set
            var activations = 0
            var closes = 0
            override fun setFailureListener(listener: () -> Unit) { failed = listener }
            override suspend fun prewarm(payload: Map<String, Any?>) {
                isPrepared = true
                connection = NativeRoomConnection(scope, {}, {
                    isPrepared = false; failed()
                }, reconnectTimeoutMillis = reconnectTimeoutMillis).also { it.observe(room) }
            }
            override suspend fun activate() { activations++ }
            override suspend fun setHeld(held: Boolean) = Unit
            override suspend fun setMuted(muted: Boolean) = Unit
            override suspend fun close(reason: String) {
                closes++; isPrepared = false
                connection?.close(); scope.cancel(); closed.complete(Unit)
            }
        }

        fun state() = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
        fun notification() = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull {
            if (notificationId != null) it.id == notificationId else
                it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == id
        }
        fun notificationPresent() = notification() != null
        @Suppress("DEPRECATION")
        fun service() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == ConversationService::class.java.name }

        suspend fun answer() {
            val grant = mapOf("provider" to "livekit", "token" to "fake-room-grant", "expiresAt" to System.currentTimeMillis() / 1000 + 120)
            assertEquals("reported", engine.incoming(IncomingCallPush(id, "incoming-$id",
                mapOf("handleType" to "generic", "value" to id, "displayName" to id), media = grant).payload())["status"])
            engine.join(id)
            withTimeout(5000) {
                while (state()?.get("status") != "connected" || !notificationPresent() || service()?.foreground != true ||
                    TelecomEvidence.calls().singleOrNull()?.contains("state=ACTIVE") != true) delay(10)
            }
            assertNotNull(state()?.get("connectedAt"))
            notificationId = notification()!!.id
            assertEquals(1, session.activations)
            assertNull(terminal)
        }

        suspend fun removedWithin(startedAt: Long, timeoutMillis: Long) {
            try {
                withTimeout(timeoutMillis) {
                    while (state() != null || notificationPresent() || service() != null || TelecomEvidence.calls().isNotEmpty()) delay(10)
                    session.closed.await(); coreClosed.await()
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("Active-call cleanup timed out: status=${state()?.get("status")}, " +
                    "Telecom=${TelecomEvidence.calls().map { if (it.contains("state=ACTIVE")) "ACTIVE" else "present" }}, " +
                    "notification=${notificationPresent()}, service=${service() != null}, " +
                    "mediaClosed=${session.closed.isCompleted}, coreClosed=${coreClosed.isCompleted}, disconnects=$disconnects", error)
            }
            assertTrue("All active-call surfaces must disappear within $timeoutMillis ms",
                SystemClock.elapsedRealtime() - startedAt <= timeoutMillis)
            assertEquals(1, terminalSnapshots)
            assertEquals(1, session.closes)
        }

        suspend fun duplicatesDoNothing() {
            val end = terminal
            val requests = disconnects
            val emitted = events.toList()
            ConversationControls.end(context, id)
            engine.remoteEnd(EndedCallPush(id, "late-push-$id", "failed").payload())
            ConversationEnds.report(context, id, "failed")
            // Let the public fire-and-forget controls and any late framework callbacks settle.
            delay(200)
            assertNull(state()); assertFalse(notificationPresent()); assertNull(service())
            assertTrue(TelecomEvidence.calls().isEmpty())
            assertEquals(end, terminal); assertEquals(1, terminalSnapshots)
            assertEquals(requests, disconnects); assertEquals(1, session.closes)
            assertEquals(emitted, events)
        }

        suspend fun close() {
            try {
                disconnectFailures = 0
                if (state() != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                // If the assertion caught an orphaned platform call, release it before the next test.
                if (TelecomEvidence.calls().isNotEmpty()) realControls?.disconnect(DisconnectCause(DisconnectCause.LOCAL))
                withTimeout(5000) {
                    while (state() != null || notificationPresent() || service() != null || TelecomEvidence.calls().isNotEmpty()) delay(10)
                    if (::session.isInitialized) session.closed.await()
                }
            } finally {
                engine.coreAdmission = previousAdmission; engine.nativeMedia = previousMedia
                engine.platformRemovalDeadlineMillis = previousRemovalDeadline
                NativeMediaSignals.observers -= observer
                engine.policy = previousPolicy; engine.emit = previousEmit
                engine.actions.resetBridge(); engine.actions.acceptUnhandled = previousAccept
                ConversationActions.configure(previousHandler)
                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previousFactory
            }
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(10) }
            val fixture = Fixture()
            try { withTimeout(12_000) { fixture.body() } }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun appReportedFailureRemovesAnAnsweredActiveCallWithinTwoSeconds() = fixture {
        answer()
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) { ConversationEnds.report(context, id, "failed") }
        removedWithin(started, 2000)
        assertEquals("failed", terminal?.get("reason"))
        assertEquals(mapOf("kind" to "server"), terminal?.get("failure"))
        duplicatesDoNothing()
    }

    @Test fun reconnectLimitEndsAnAnsweredActiveCallWithMediaFailure() = fixture {
        reconnectTimeoutMillis = 500
        answer()
        val started = SystemClock.elapsedRealtime()
        session.room.emit(NativeRoomState.RECONNECTING)
        assertEquals("connected", state()?.get("status")); assertNull(terminal)
        removedWithin(started, 3000)
        assertTrue("The real reconnect timer must expire before teardown", SystemClock.elapsedRealtime() - started >= reconnectTimeoutMillis)
        assertEquals("failed", terminal?.get("reason"))
        assertEquals(mapOf("kind" to "media"), terminal?.get("failure"))
        duplicatesDoNothing()
    }

    @Test fun twoCoreDisconnectErrorsCannotLeaveAnAnsweredCallInTelecom() = exhaustedDisconnect(false)
    @Test fun twoThrownCoreDisconnectFailuresCannotLeaveAnAnsweredCallInTelecom() = exhaustedDisconnect(true)

    private fun exhaustedDisconnect(throws: Boolean) = fixture {
        answer()
        disconnectFailures = 2; throwDisconnectFailures = throws
        val started = SystemClock.elapsedRealtime()
        ConversationControls.end(context, id)
        removedWithin(started, 3000)
        assertEquals(3, disconnects)
        assertEquals("local", terminal?.get("reason")); assertNull(terminal?.get("failure"))
        assertEquals(1, events.count { it["type"] == "actionPerformed" &&
            ((it["payload"] as Map<*, *>)["action"] as Map<*, *>)["kind"] == "end" })
        duplicatesDoNothing()
    }

    @Test fun refreshLeavesRetainedEndingCardAndSignalsUnchanged() = fixture {
        answer()
        disconnectFailures = Int.MAX_VALUE
        ConversationEnds.report(context, id, "remoteEnded")
        withTimeout(5000) { while (notification()?.notification?.extras?.getString(Notification.EXTRA_TITLE) != "Ending call…") delay(10) }
        val original = notification()!!
        var customizations = 0
        ConversationPresentation.configure { _, _ -> customizations++; CallNotificationContent(title = "Must not appear") }
        try {
            val snapshot = engine.snapshot
            val beforeEvents = events.toList()
            val beforeSignals = signals.toList()
            withContext(Dispatchers.Default) {
                ConversationPresentation.refresh(context)
                withContext(Dispatchers.Main) { }
            }
            val after = notification()!!
            assertEquals(original.id, after.id); assertEquals(original.postTime, after.postTime)
            assertEquals("Ending call…", after.notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(listOf("End"), after.notification.actions.map { it.title.toString() })
            assertTrue(service()?.foreground == true)
            assertEquals(0, customizations)
            assertSame(snapshot, engine.snapshot)
            assertEquals(beforeEvents, events); assertEquals(beforeSignals, signals)
        } finally {
            ConversationPresentation.configure(null)
            disconnectFailures = 0
            engine.retryPlatformRemoval(id)
        }
        removedWithin(SystemClock.elapsedRealtime(), 3000)
    }

    @Test fun permanentFailureKeepsEndingCardUntilDeadlineThenSignalsAndCleansUpLocally() = fixture {
        engine.platformRemovalDeadlineMillis = 2000
        // The deadline is captured when the call is created.
        answer()
        disconnectFailures = Int.MAX_VALUE
        val originalNotificationId = notification()!!.id
        val started = SystemClock.elapsedRealtime()
        ConversationEnds.report(context, id, "failed")
        withTimeout(1000) { while (terminal == null || notification()?.notification?.extras?.getString(Notification.EXTRA_TITLE) != "Ending call…") delay(10) }
        session.closed.await()
        assertEquals("failed", terminal?.get("reason"))
        assertEquals(mapOf("kind" to "server"), terminal?.get("failure"))
        assertEquals(1, events.count { it["type"] == "audioDeactivated" })
        assertFalse(coreClosed.isCompleted)
        assertNull(state())
        withTimeout(1000) { while (disconnects < 3) delay(10) }
        val card = notification()!!
        assertEquals(originalNotificationId, card.id)
        assertEquals(Notification.FLAG_ONGOING_EVENT, card.notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(listOf("End"), card.notification.actions.map { it.title.toString() })
        val attemptsBeforeAction = disconnects
        card.notification.actions.single().actionIntent.send()
        withTimeout(250) { while (disconnects == attemptsBeforeAction) delay(5) }
        assertFalse(coreClosed.isCompleted)
        while (signals.none { it.stage == NativeMediaStage.PLATFORM_REMOVAL_FAILED }) {
            if (SystemClock.elapsedRealtime() - started < 2000) {
                assertEquals("Ending call…", notification()?.notification?.extras?.getString(Notification.EXTRA_TITLE))
                assertTrue(service()?.foreground == true)
                assertFalse(coreClosed.isCompleted)
            }
            delay(10)
        }
        assertTrue(SystemClock.elapsedRealtime() - started >= 2000)
        val failed = signals.single { it.stage == NativeMediaStage.PLATFORM_REMOVAL_FAILED }
        assertEquals(id, failed.serverCallId)
        assertEquals(mapOf("attemptCount" to disconnects.toLong(), "lastCoreErrorCode" to CallException.ERROR_UNKNOWN.toLong()), failed.measurements)
        assertNull(failed.reason)
        withTimeout(1000) { while (notificationPresent() || service() != null || !coreClosed.isCompleted) delay(10) }
        assertTrue("Refusing Core removal can leave the system call behind", TelecomEvidence.calls().isNotEmpty())
        assertEquals(mapOf("kind" to "server"), terminal?.get("failure"))
        assertEquals(1, terminalSnapshots); assertEquals(1, session.closes)
    }
}
