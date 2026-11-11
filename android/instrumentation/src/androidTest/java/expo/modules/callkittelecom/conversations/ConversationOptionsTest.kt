package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.firebase.messaging.RemoteMessage
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationOptionsTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private class ManualClock : Clock() {
        private data class Wait(val until: Long, val ready: CompletableDeferred<Unit>)
        private var now = 1_900_000_000_000L
        private val waits = mutableSetOf<Wait>()
        val pending: Int get() = waits.size
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock { require(zone == ZoneOffset.UTC); return this }
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long = now
        suspend fun sleep(millis: Long) {
            if (millis <= 0) return
            val wait = Wait(Math.addExact(now, millis), CompletableDeferred())
            waits += wait
            try { wait.ready.await() } finally { waits -= wait }
        }
        fun advanceBy(millis: Long) {
            now = Math.addExact(now, millis)
            waits.filter { it.until <= now }.forEach { it.ready.complete(Unit) }
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = ConversationEngine.get(context)
        private val previousDeadline = engine.incomingRingDeadline
        private val previousTimeout = engine.incomingTimeoutMillis
        private val previousCapabilities = engine.capabilities
        private val previousMedia = engine.nativeMedia
        private val previousEmit = engine.emit
        private val ids = mutableListOf<String>()
        val ended = mutableMapOf<String, Map<*, *>>()
        val endedAt = mutableMapOf<String, Long>()
        val ringing = mutableSetOf<String>()
        val signals = mutableListOf<NativeMediaSignal>()
        private val observer = NativeMediaObserver { signals += it }
        init {
            NativeMediaSignals.observers += observer
            engine.nativeMedia = AndroidNativeMedia.NONE
            engine.incomingTimeoutMillis = 45_000
            engine.capabilities = ConversationCapabilities()
            engine.emit = { name, body -> if (name == "conversationsChanged") {
                for (state in (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>()) {
                    val id = state["id"] as String
                    if (state["status"] == "ringing") ringing += id
                    if (state["status"] == "ended") {
                        ended[id] = state["ended"] as Map<*, *>
                        endedAt[id] = System.currentTimeMillis()
                    }
                }
            } }
        }
        fun state(id: String): Map<*, *>? = (engine.snapshot["conversations"] as List<*>)
            .filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
        fun push(expiry: Long? = null): IncomingCallPush {
            val id = "options-${System.nanoTime()}"
            ids += id
            return IncomingCallPush(id, "$id-invite", mapOf("handleType" to "generic", "value" to "caller",
                "displayName" to "Options caller"), expiresAtMillis = expiry)
        }
        suspend fun incoming(push: IncomingCallPush): String {
            assertEquals("reported", engine.incoming(push.payload())["status"])
            return push.serverCallId
        }
        @Suppress("DEPRECATION")
        suspend fun removed(id: String) = withTimeout(5000) {
            while (state(id) != null || TelecomEvidence.calls().isNotEmpty() ||
                context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.notification.category == Notification.CATEGORY_CALL } ||
                context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == ConversationService::class.java.name }) delay(10)
        }
        suspend fun close() {
            try {
                withContext(NonCancellable) {
                    for (id in ids) runCatching { withTimeout(5000) { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) } }
                    ids.lastOrNull()?.let { removed(it) }
                }
            } finally {
                CallPushIntake.configure()
                engine.incomingRingDeadline = previousDeadline
                engine.incomingTimeoutMillis = previousTimeout
                engine.capabilities = previousCapabilities
                engine.nativeMedia = previousMedia
                engine.emit = previousEmit
                NativeMediaSignals.observers -= observer
            }
        }
    }

    private suspend fun withEngine(body: suspend Fixture.() -> Unit) = withContext(Dispatchers.Main) {
        val fixture = Fixture()
        try { fixture.body() } finally { fixture.close() }
    }

    @Test fun engineCapsExplicitExpiryAtTheConfiguredRingDeadline() = runBlocking {
        withEngine {
            val clock = ManualClock()
            engine.incomingRingDeadline = IncomingRingDeadline(clock, clock::sleep)
            for (duration in listOf(5_000L, 60_000L, null)) {
                val push = push(duration?.let { clock.millis() + it })
                val id = incoming(push)
                assertEquals(1, clock.pending)
                clock.advanceBy(minOf(duration ?: 45_000, 45_000) - 1)
                yield()
                assertEquals("ringing", state(id)?.get("status"))
                clock.advanceBy(1)
                removed(id)
                assertEquals("unanswered", ended[id]?.get("reason"))
                assertEquals(0, clock.pending)
            }
        }
    }

    @Test fun duplicatePushCannotExtendTheOriginalExpiryAndAnswerCancelsIt() = runBlocking {
        withEngine {
            val clock = ManualClock()
            engine.incomingRingDeadline = IncomingRingDeadline(clock, clock::sleep)
            val first = push(clock.millis() + 5_000)
            val id = incoming(first)
            clock.advanceBy(4_000)
            assertEquals(id, engine.incoming(first.copy(eventId = "${first.eventId}-retry", expiresAtMillis = clock.millis() + 60_000).payload())["id"])
            clock.advanceBy(1_000)
            removed(id)
            assertEquals("unanswered", ended[id]?.get("reason"))

            val answered = incoming(push(clock.millis() + 5_000))
            engine.join(answered)
            engine.report(answered, mapOf("type" to "connected"))
            yield()
            assertEquals(0, clock.pending)
            clock.advanceBy(60_000)
            yield()
            assertEquals("connected", state(answered)?.get("status"))
            assertNull(ended[answered])
            engine.report(answered, mapOf("type" to "ended", "reason" to "remoteEnded"))
            removed(answered)
        }
    }

    @Test fun remoteEndCancelsTheExpiryAndPastExpiryEndsImmediately() = runBlocking {
        withEngine {
            val clock = ManualClock()
            engine.incomingRingDeadline = IncomingRingDeadline(clock, clock::sleep)
            val id = incoming(push(clock.millis() + 5_000))
            engine.remoteEnd(EndedCallPush(id, "$id-end", "answeredElsewhere").payload())
            removed(id)
            assertEquals(0, clock.pending)
            clock.advanceBy(60_000)
            yield()
            assertEquals("answeredElsewhere", ended[id]?.get("reason"))
            val expired = push(clock.millis() - 1)
            assertEquals("reported", engine.incoming(expired.payload())["status"])
            removed(expired.serverCallId)
            assertEquals("unanswered", ended[expired.serverCallId]?.get("reason"))
            assertFalse(ringing.contains(expired.serverCallId))
            assertFalse(engine.canWarmIncoming(expired.serverCallId))
            assertEquals("reported", engine.incoming(expired.payload())["status"])
            yield()
            assertEquals(1, signals.count { it.serverCallId == expired.serverCallId && it.stage == NativeMediaStage.CORE_ADMITTED })
        }
    }

    @Test fun expiryDuringPolicyEvaluationKeepsTheReportWithoutLatePresentation() = runBlocking {
        withEngine {
            val clock = ManualClock()
            engine.incomingRingDeadline = IncomingRingDeadline(clock, clock::sleep)
            val push = push(clock.millis() + 5_000)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val previous = NativeIncomingFilters.evaluate
            NativeIncomingFilters.evaluate = { entered.complete(Unit); gate.await(); null }
            try {
                coroutineScope {
                    val pending = async { engine.incoming(push.payload()) }
                    try {
                        withTimeout(5000) { entered.await() }
                        clock.advanceBy(5_000)
                        removed(push.serverCallId)
                        assertEquals("unanswered", ended[push.serverCallId]?.get("reason"))
                        gate.complete(Unit)
                        assertEquals("reported", withTimeout(5000) { pending.await() }["status"])
                        assertFalse(ringing.contains(push.serverCallId))
                        assertFalse(engine.canWarmIncoming(push.serverCallId))
                        assertEquals("reported", engine.incoming(push.payload())["status"])
                        yield()
                        assertEquals(1, signals.count { it.serverCallId == push.serverCallId && it.stage == NativeMediaStage.CORE_ADMITTED })
                    } finally {
                        gate.complete(Unit)
                        pending.cancelAndJoin()
                    }
                }
            } finally { NativeIncomingFilters.evaluate = previous }
        }
    }

    @Test fun heldButUnansweredIncomingCallStillExpiresWithExplicitAndDefaultDeadlines() = runBlocking {
        withEngine {
            val clock = ManualClock()
            engine.incomingRingDeadline = IncomingRingDeadline(clock, clock::sleep)
            for (duration in listOf(5_000L, null)) {
                val id = incoming(push(duration?.let { clock.millis() + it }))
                engine.hold(id, true, NativeActionOrigin.SYSTEM)
                assertEquals("held", state(id)?.get("status"))
                clock.advanceBy(duration ?: 45_000)
                removed(id)
                assertEquals("unanswered", ended[id]?.get("reason"))
            }
        }
    }

    @Test fun decodedPushExpiresThroughRealTelecomAndClearsRingingSurfaces() = runBlocking {
        withEngine {
            engine.incomingRingDeadline = IncomingRingDeadline()
            val push = push(System.currentTimeMillis() + 5_000)
            CallPushIntake.configure()
            val message = RemoteMessage.Builder("test-sender").addData("messageType", "incomingCall")
                .addData("incomingCall", JSONObject(push.payload()).toString()).build()
            assertTrue(CallPushIntake.handle(context, message))
            withTimeout(10_000) { while (ended[push.serverCallId] == null) delay(10) }
            removed(push.serverCallId)
            assertTrue(ringing.contains(push.serverCallId))
            assertEquals("unanswered", ended[push.serverCallId]?.get("reason"))
            assertTrue(endedAt[push.serverCallId]!! >= push.expiresAtMillis!!)
        }
    }

    @Test fun disabledHoldRejectsAppAndSystemRequestsAndKeepsAnswerWorking() = runBlocking {
        withEngine {
            engine.capabilities = ConversationCapabilities(supportsHold = false)
            val id = incoming(push())
            assertEquals(false, (state(id)?.get("capabilities") as Map<*, *>)["hold"])
            // Configuration changes apply to new calls, never to an existing call's advertised support.
            engine.capabilities = ConversationCapabilities()
            for (held in listOf(true, false)) {
                val error = runCatching { engine.hold(id, held) }.exceptionOrNull() as Exception
                assertEquals("unsupported", CommandRejection.from(error).code)
                assertEquals("ringing", state(id)?.get("status"))
            }
            val systemError = runCatching { engine.hold(id, true, NativeActionOrigin.SYSTEM) }.exceptionOrNull() as Exception
            assertEquals("unsupported", CommandRejection.from(systemError).code)
            engine.join(id)
            engine.report(id, mapOf("type" to "connected"))
            assertEquals("connected", state(id)?.get("status"))
            assertEquals(false, (state(id)?.get("capabilities") as Map<*, *>)["hold"])
            val error = runCatching { engine.hold(id, true) }.exceptionOrNull() as Exception
            assertEquals("unsupported", CommandRejection.from(error).code)
            assertEquals("connected", state(id)?.get("status"))
        }
    }
}
