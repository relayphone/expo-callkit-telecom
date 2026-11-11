package expo.modules.callkittelecom.conversations

import android.Manifest
import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationCorrectnessTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private class Core : CoreCallAdmission {
        val entered = CompletableDeferred<Unit>()
        val admit = CompletableDeferred<Unit>()
        val removed = CompletableDeferred<Unit>()
        val confirmed = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val disconnectEntered = CompletableDeferred<Unit>()
        var disconnectGate: CompletableDeferred<Unit>? = null
        var retryGate: CompletableDeferred<Unit>? = null
        val retryEntered = CompletableDeferred<Unit>()
        var disconnects = 0
        var throwsRemaining = 0
        val outcomes = ArrayDeque<CallControlResult>()
        val muted = MutableSharedFlow<Boolean>(replay = 1)
        val endpoint = MutableStateFlow<CallEndpointCompat?>(null)
        lateinit var callbacks: CoreCallCallbacks

        override suspend fun add(attributes: CallAttributesCompat, callbacks: CoreCallCallbacks, admitted: CallControlScope.() -> Unit) {
            this.callbacks = callbacks
            try { coroutineScope {
                entered.complete(Unit)
                admit.await()
                val controls = object : CallControlScope {
                    override val coroutineContext: CoroutineContext = this@coroutineScope.coroutineContext
                    override fun getCallId() = ParcelUuid(UUID.randomUUID())
                    override val currentCallEndpoint = endpoint.filterNotNull()
                    override val availableEndpoints = flowOf(emptyList<CallEndpointCompat>())
                    override val isMuted: Flow<Boolean> = muted
                    override suspend fun setActive() = CallControlResult.Success()
                    override suspend fun setInactive() = CallControlResult.Success()
                    override suspend fun answer(callType: Int) = CallControlResult.Success()
                    override suspend fun requestEndpointChange(endpoint: CallEndpointCompat) = CallControlResult.Success()
                    override suspend fun disconnect(cause: DisconnectCause): CallControlResult {
                        disconnects++
                        disconnectEntered.complete(Unit)
                        disconnectGate?.await()
                        if (disconnects == 2) { retryEntered.complete(Unit); retryGate?.await() }
                        if (throwsRemaining > 0) { throwsRemaining--; error("Core disconnect failed") }
                        val outcome = outcomes.removeFirstOrNull() ?: CallControlResult.Success()
                        if (outcome is CallControlResult.Success || outcome is CallControlResult.Error &&
                            outcome.errorCode == CallException.ERROR_CALL_IS_NOT_BEING_TRACKED) confirmed.complete(Unit)
                        removed.complete(Unit)
                        return outcome
                    }
                }
                admitted(controls)
                removed.await()
            } } finally { closed.complete(Unit) }
        }
    }

    private class Fixture {
        val engine = ConversationEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = "correctness-${System.nanoTime()}"
        val core = Core()
        private val previousAdmission = engine.coreAdmission
        private val previousMedia = engine.nativeMedia
        private val previousPolicy = engine.policy
        private val previousEmit = engine.emit
        private val previousAccept = engine.actions.acceptUnhandled
        val signals = mutableListOf<NativeMediaSignal>()
        val performed = mutableListOf<Map<*, *>>()
        val events = mutableListOf<Map<String, Any?>>()
        private val observer = NativeMediaObserver { signals += it }
        var ended: Map<*, *>? = null

        init {
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            engine.coreAdmission = core; engine.nativeMedia = AndroidNativeMedia.NONE
            engine.policy = JSONObject(mapOf("prewarm" to false))
            engine.emit = { name, body ->
                if (name == "conversationEvent") events += body
                if (name == "conversationEvent" && body["type"] == "actionPerformed") performed += body["payload"] as Map<*, *>
                if (name == "conversationsChanged") (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
                    .firstOrNull { it["id"] == id && it["status"] == "ended" }?.let { ended = it["ended"] as Map<*, *> }
            }
            ConversationActions.configure(null)
            NativeMediaSignals.observers += observer
        }
        fun state() = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
        suspend fun start() { engine.start(mapOf("id" to id, "to" to mapOf("id" to id))) }
        suspend fun ready() { core.admit.complete(Unit); start() }
        suspend fun incoming() {
            core.admit.complete(Unit)
            assertEquals("reported", engine.incoming(IncomingCallPush(id, "incoming-$id",
                mapOf("handleType" to "generic", "value" to id, "displayName" to id)).payload())["status"])
        }
        suspend fun ended() = withTimeout(5000) {
            if (core.disconnects > 0) core.confirmed.await()
            while (state() != null || !core.closed.isCompleted) delay(10)
        }
        suspend fun close() {
            try {
                core.admit.complete(Unit); core.disconnectGate?.complete(Unit); core.retryGate?.complete(Unit)
                if (state() != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(5000) { while (state() != null) delay(10) }
            } finally {
                NativeMediaSignals.observers -= observer
                engine.coreAdmission = previousAdmission; engine.nativeMedia = previousMedia
                engine.policy = previousPolicy; engine.emit = previousEmit; engine.actions.acceptUnhandled = previousAccept
            }
        }
    }
    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val fixture = Fixture()
            try { withTimeout(10_000) { fixture.body() } } finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun appMuteBeforeAdmissionSurvivesCoreBaselineAndLaterSystemMuteApplies() = fixture {
        coroutineScope {
            val starting = async { start() }
            core.entered.await()
            engine.mute(id, true)
            core.admit.complete(Unit); starting.await()
            core.muted.emit(false)
            withTimeout(5000) { while (signals.none { it.stage == NativeMediaStage.CORE_MUTE_OBSERVED }) delay(10) }
            assertEquals(true, state()?.get("isMuted"))
            engine.mute(id, false)
            core.muted.emit(true)
            withTimeout(5000) { while (state()?.get("isMuted") != true) delay(10) }
            engine.end(id); ended()
        }
    }

    @Test fun overlappingLocalEndsShareOneCoreDisconnect() = fixture {
        ready()
        core.disconnectGate = CompletableDeferred()
        coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { engine.end(id) }
            core.disconnectEntered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { engine.end(id) }
            assertEquals(1, core.disconnects)
            core.disconnectGate!!.complete(Unit)
            awaitAll(first, second)
        }
        ended()
        assertEquals(1, core.disconnects)
        assertEquals("local", ended?.get("reason")); assertNull(ended?.get("failure"))
        assertEquals(1, performed.count { (it["action"] as Map<*, *>)["kind"] == "end" })
    }

    @Test fun joiningLogicallyEndedCallRejectsUnknownWhilePlatformRemovalIsPending() = fixture {
        incoming()
        engine.join(id)
        core.disconnectGate = CompletableDeferred()
        engine.end(id)
        core.disconnectEntered.await()
        assertNull(state())
        assertFalse(core.closed.isCompleted)
        val error = runCatching { engine.join(id) }.exceptionOrNull()
        assertNotNull("Joining an ended call must reject", error)
        assertEquals("unknown_conversation", CommandRejection.from(error as Exception).code)
        assertEquals(1, core.disconnects)
        core.disconnectGate!!.complete(Unit)
        ended()
    }

    @Test fun twoFailedDisconnectsRetryUntilConfirmedRemoval() = fixture {
        ready()
        core.outcomes += CallControlResult.Error(CallException.ERROR_UNKNOWN)
        core.outcomes += CallControlResult.Error(CallException.ERROR_UNKNOWN)
        engine.end(id); ended()
        assertEquals(3, core.disconnects)
        assertEquals("local", ended?.get("reason")); assertNull(ended?.get("failure"))
    }

    @Test fun coreLifetimeClosingOnErrorDoesNotPreventLogicalEndOrConfirmedRetry() = fixture {
        ready()
        core.outcomes += CallControlResult.Error(CallException.ERROR_UNKNOWN)
        core.retryGate = CompletableDeferred()
        coroutineScope {
            val ending = async { engine.end(id) }
            core.retryEntered.await(); core.closed.await()
            assertEquals("local", ended?.get("reason"))
            assertNull(state())
            assertFalse(core.confirmed.isCompleted)
            core.retryGate!!.complete(Unit)
            ending.await()
        }
        ended()
        assertEquals(2, core.disconnects)
        assertEquals("local", ended?.get("reason"))
    }

    @Test fun remoteEndAlreadyDisconnectingKeepsItsReasonWhenLocalEndOverlaps() = fixture {
        ready(); core.disconnectGate = CompletableDeferred()
        coroutineScope {
            val remote = async { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
            core.disconnectEntered.await()
            engine.end(id)
            assertEquals(1, core.disconnects)
            core.disconnectGate!!.complete(Unit); remote.await()
        }
        ended()
        assertEquals("remoteEnded", ended?.get("reason")); assertNull(ended?.get("failure"))
    }

    @Test fun remoteReportAfterLogicalEndCannotReplaceItsReason() = reportJoiningLocalDisconnect("remoteEnded")
    @Test fun failedReportAfterLogicalEndCannotReplaceItsFailure() = reportJoiningLocalDisconnect("failed")

    private fun reportJoiningLocalDisconnect(reason: String) = fixture {
        ready(); core.disconnectGate = CompletableDeferred()
        coroutineScope {
            val local = async { runCatching { engine.end(id) } }
            core.disconnectEntered.await()
            engine.report(id, mapOf("type" to "ended", "reason" to reason))
            assertEquals(1, core.disconnects)
            core.disconnectGate!!.complete(Unit); local.await()
        }
        ended()
        assertEquals("local", ended?.get("reason")); assertNull(ended?.get("failure"))
        assertEquals(1, performed.count { (it["action"] as Map<*, *>)["kind"] == "end" })
    }

    @Test fun cancellingAReportCannotLetALaterReportReplaceItsReason() = fixture {
        ready(); core.disconnectGate = CompletableDeferred()
        coroutineScope {
            val remote = async { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
            core.disconnectEntered.await()
            remote.cancelAndJoin()
            engine.report(id, mapOf("type" to "ended", "reason" to "failed"))
            assertEquals(1, core.disconnects)
            core.disconnectGate!!.complete(Unit)
        }
        ended()
        assertEquals("remoteEnded", ended?.get("reason")); assertNull(ended?.get("failure"))
        assertEquals(0, performed.count { (it["action"] as Map<*, *>)["kind"] == "end" })
    }

    @Test fun aLateLocalHandlerCannotReplaceAnAlreadyDisconnectingRemoteEnd() = fixture {
        ready(); core.disconnectGate = CompletableDeferred()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ConversationActions.configure { entered.complete(Unit); release.await(); ConversationActionResult.Proceed() }
        try { coroutineScope {
            val local = async { runCatching { engine.end(id) } }
            entered.await()
            val remote = async { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
            core.disconnectEntered.await()
            release.complete(Unit)
            assertTrue(local.await().isFailure)
            core.disconnectGate!!.complete(Unit); remote.await()
        } } finally { release.complete(Unit); ConversationActions.configure(null) }
        ended()
        assertEquals(1, core.disconnects)
        assertEquals("remoteEnded", ended?.get("reason"))
        assertEquals(0, performed.count { (it["action"] as Map<*, *>)["kind"] == "end" })
    }

    @Test fun cancellingTheFirstLocalEndDoesNotCancelAnotherEndOrDuplicateCoreRequest() = fixture {
        ready(); core.disconnectGate = CompletableDeferred()
        coroutineScope {
            val first = async { engine.end(id) }
            core.disconnectEntered.await()
            first.cancelAndJoin()
            val second = async(start = CoroutineStart.UNDISPATCHED) { engine.end(id) }
            assertEquals(1, core.disconnects)
            core.disconnectGate!!.complete(Unit); second.await()
        }
        ended()
        assertEquals(1, core.disconnects)
        assertEquals("local", ended?.get("reason"))
    }

    @Test fun cancelledOnlyCallerStillCleansUpAfterTwoThrownDisconnectFailures() = fixture {
        ready(); core.disconnectGate = CompletableDeferred(); core.throwsRemaining = 2
        coroutineScope {
            val local = async { engine.end(id) }
            core.disconnectEntered.await(); local.cancelAndJoin()
            core.disconnectGate!!.complete(Unit)
            ended()
        }
        assertEquals(3, core.disconnects)
        assertEquals("local", ended?.get("reason"))
    }

    @Test fun lateServerAnsweredElsewhereCannotReplaceLogicalEnd() = serverEndJoiningDisconnect("answeredElsewhere")
    @Test fun lateServerFailureCannotReplaceLogicalEnd() = serverEndJoiningDisconnect("failed")
    @Test fun lateServerEndCannotReplaceLogicalRemoteReport() = serverEndJoiningDisconnect("answeredElsewhere", reportFirst = true)

    private fun serverEndJoiningDisconnect(reason: String, reportFirst: Boolean = false) = fixture {
        incoming(); core.disconnectGate = CompletableDeferred()
        coroutineScope {
            val local = async { runCatching {
                if (reportFirst) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) else engine.end(id)
            } }
            core.disconnectEntered.await()
            assertTrue(engine.receiveCallEnded(mapOf("serverCallId" to id, "eventId" to "ended-$id", "reason" to reason)))
            core.disconnectGate!!.complete(Unit); local.await()
        }
        ended()
        assertEquals(1, core.disconnects)
        assertEquals(if (reportFirst) "remoteEnded" else "local", ended?.get("reason"))
        assertNull(ended?.get("failure"))
        assertEquals(0, events.count { it["type"] == "answeredElsewhere" })
    }

    @Test fun notTrackedDoesNotRetryAndRemoteReasonIsPreserved() = fixture {
        ready()
        core.outcomes += CallControlResult.Error(CallException.ERROR_CALL_IS_NOT_BEING_TRACKED)
        engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")); ended()
        assertEquals(1, core.disconnects)
        assertEquals("remoteEnded", ended?.get("reason")); assertNull(ended?.get("failure"))
    }

    @Test fun unrequestedCoreRemovalIsAFrameworkFailure() = fixture {
        ready()
        core.removed.complete(Unit); ended()
        assertEquals(0, core.disconnects)
        assertEquals("failed", ended?.get("reason"))
        assertEquals(mapOf("kind" to "frameworkReset"), ended?.get("failure"))
    }

    @Test fun explicitRemoteCoreRemovalIsRemoteWithoutAnotherDisconnectOrPersonAction() = coreRemoval(DisconnectCause.REMOTE, "remoteEnded")
    @Test fun explicitCoreErrorIsAFrameworkFailureWithoutAnotherDisconnectOrPersonAction() = coreRemoval(DisconnectCause.ERROR, "failed")

    private fun coreRemoval(cause: Int, reason: String) = fixture {
        ready()
        core.callbacks.disconnect(DisconnectCause(cause))
        core.removed.complete(Unit); ended()
        assertEquals(0, core.disconnects)
        assertEquals(reason, ended?.get("reason"))
        if (reason == "failed") assertEquals(mapOf("kind" to "frameworkReset"), ended?.get("failure"))
        else assertNull(ended?.get("failure"))
        assertEquals(0, performed.count { (it["action"] as Map<*, *>)["kind"] == "end" })
    }

    @Test fun proximityTracksCoreRoutesHoldResumeAndPendingEnd() = fixture {
        val previous = engine.proximity
        val events = mutableListOf<Boolean>()
        engine.proximity = ConversationProximity(object : ProximityWakeLock {
            override fun acquire() { events += true }
            override fun release() { events += false }
        })
        try {
            ready(); engine.report(id, mapOf("type" to "connected"))
            suspend fun route(type: Int, count: Int) {
                core.endpoint.value = CallEndpointCompat("route", type, ParcelUuid(UUID.randomUUID()))
                withTimeout(3000) { while (events.size != count) delay(10) }
            }
            assertTrue(events.isEmpty())
            route(CallEndpointCompat.TYPE_EARPIECE, 1); route(CallEndpointCompat.TYPE_SPEAKER, 2)
            route(CallEndpointCompat.TYPE_EARPIECE, 3); route(CallEndpointCompat.TYPE_BLUETOOTH, 4)
            route(CallEndpointCompat.TYPE_EARPIECE, 5); route(CallEndpointCompat.TYPE_WIRED_HEADSET, 6)
            route(CallEndpointCompat.TYPE_EARPIECE, 7)
            engine.hold(id, true); assertEquals(8, events.size)
            engine.hold(id, false); assertEquals(9, events.size)
            core.disconnectGate = CompletableDeferred()
            coroutineScope {
                val ending = async { engine.end(id) }
                core.disconnectEntered.await()
                assertEquals(10, events.size)
                assertFalse(events.last())
                core.disconnectGate!!.complete(Unit); ending.await()
            }
            ended()
            assertEquals(List(5) { listOf(true, false) }.flatten(), events)
        } finally { engine.proximity.update(false, null); engine.proximity = previous }
    }

    @Test fun systemUserEndIsLocalWithoutRequestingAnotherDisconnect() = fixture {
        ready()
        core.callbacks.disconnect(DisconnectCause(DisconnectCause.LOCAL))
        core.removed.complete(Unit); ended()
        assertEquals(0, core.disconnects)
        assertEquals("local", ended?.get("reason")); assertNull(ended?.get("failure"))
        assertEquals(1, performed.count { it["origin"] == "system" && (it["action"] as Map<*, *>)["kind"] == "end" })
    }
}
