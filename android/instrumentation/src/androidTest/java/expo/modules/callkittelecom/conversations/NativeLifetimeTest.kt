package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLifetimeTest {
    @Test fun nativeDeadlineAndReplacement() = runBlocking {
        withContext(Dispatchers.Main) {
            val deliveries = mutableListOf<Map<String, Any?>>()
            val coordinator = ActionCoordinator({ name, body -> if (name == "handlerAction") deliveries += body }, {})
            val conversation = mapOf<String, Any?>("id" to "test-conversation")
            val pending = async { coordinator.perform(conversation, "join", "system") }
            yield()
            assertNotNull(coordinator.pending("test-conversation"))
            coordinator.attach(true, 1, coordinator.epoch)
            assertEquals(1, deliveries.size)
            coordinator.attach(true, 2, coordinator.epoch)
            assertEquals(2, deliveries.size)
            val id = deliveries[0]["actionID"] as String
            coordinator.settle(id, 1, false, coordinator.epoch)
            assertFalse(pending.isCompleted)
            coordinator.settle(id, 2, true, coordinator.epoch)
            pending.await()
            assertNull(coordinator.pending("test-conversation"))
            val began = android.os.SystemClock.elapsedRealtime()
            val failure = runCatching { coordinator.perform(conversation, "join", "system") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(android.os.SystemClock.elapsedRealtime() - began in 3300..4500)
            assertNull(coordinator.pending("test-conversation"))
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

    @Test fun bridgeEpochAndColdBudgetCannotBeReused() = runBlocking {
        withContext(Dispatchers.Main) {
            val deliveries = mutableListOf<Map<String, Any?>>()
            val coordinator = ActionCoordinator({ name, body -> if (name == "handlerAction") deliveries += body }, {})
            coordinator.coldJoinBudgetMs = 60_000
            assertEquals(3500L, coordinator.coldJoinBudgetMs)
            assertTrue(runCatching { coordinator.coldJoinBudgetMs = 0 }.isFailure)
            val oldEpoch = coordinator.epoch
            coordinator.attach(true, 1, oldEpoch)
            val pending = async { coordinator.perform(mapOf("id" to "epoch"), "join", "system") }
            yield()
            val id = deliveries.first()["actionID"] as String
            coordinator.resetBridge()
            coordinator.attach(true, 1, coordinator.epoch)
            coordinator.settle(id, 1, true, oldEpoch)
            assertFalse(pending.isCompleted)
            coordinator.settle(id, 1, true, coordinator.epoch)
            pending.await()
        }
    }

    @Test fun timeoutFailsAndMuteCannotOutliveClose() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val failed = CompletableDeferred<Unit>()
            val muted = CompletableDeferred<Unit>()
            val muteCancelled = CompletableDeferred<Unit>()
            var closed = CompletableDeferred<Unit>()
            var muteCalls = 0
            NativeMediaRegistry.factories["webrtc"] = {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) {}
                    override suspend fun activate() {}
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(mutedValue: Boolean) {
                        if (mutedValue) {
                            muteCalls++; muted.complete(Unit)
                            try { awaitCancellation() } finally { muteCancelled.complete(Unit) }
                        }
                    }
                    override suspend fun close(reason: String) { closed.complete(Unit) }
                }
            }
            NativeMediaRegistry.fetchers["slow"] = { awaitCancellation() }
            try {
                val timeoutPolicy = JSONObject(mapOf("media" to "webrtc", "prewarm" to false,
                    "credentials" to mapOf("name" to "slow")))
                val first = NativeMediaController(context, emptyMap(), timeoutPolicy, owner, { failed.complete(Unit) }, {})
                first.answered {}
                withTimeout(4500) { failed.await() }
                first.close("failed")
                withTimeout(3000) { closed.await() }
                closed = CompletableDeferred()
                val connected = CompletableDeferred<Unit>()
                val payload = mapOf("media" to mapOf("expiresAt" to System.currentTimeMillis() / 1000 + 120))
                val second = NativeMediaController(context, payload, JSONObject(mapOf("media" to "webrtc")), owner, {}, { connected.complete(Unit) })
                second.prewarm(); second.answered {}
                withTimeout(3000) { connected.await() }
                val pendingMute = owner.async { runCatching { second.mute() } }
                withTimeout(3000) { muted.await() }
                second.close("ended")
                withTimeout(3000) { closed.await(); muteCancelled.await(); pendingMute.await() }
                assertTrue(runCatching { second.mute() }.isFailure)
                assertEquals(1, muteCalls)
                closed = CompletableDeferred()
                val thirdConnected = CompletableDeferred<Unit>()
                val third = NativeMediaController(context, payload, JSONObject(mapOf("media" to "webrtc")), owner, {}, { thirdConnected.complete(Unit) })
                third.prewarm(); third.answered {}
                withTimeout(3000) { thirdConnected.await() }
                owner.cancel()
                third.close("ended")
                withTimeout(3000) { closed.await() }
            } finally {
                NativeMediaRegistry.factories.remove("webrtc"); NativeMediaRegistry.fetchers.remove("slow"); owner.cancel()
            }
        }
    }

    @Test fun unmuteDuringActivationUsesLatestDesiredState() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val connected = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            var nativeMuted = false
            NativeMediaRegistry.factories["webrtc"] = {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) {}
                    override suspend fun activate() { entered.complete(Unit); gate.await() }
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(muted: Boolean) { nativeMuted = muted }
                    override suspend fun close(reason: String) { closed.complete(Unit) }
                }
            }
            try {
                val payload = mapOf("media" to mapOf("expiresAt" to System.currentTimeMillis() / 1000 + 120))
                val controller = NativeMediaController(InstrumentationRegistry.getInstrumentation().targetContext,
                    payload, JSONObject(mapOf("media" to "webrtc")), owner, {}, { connected.complete(Unit) })
                controller.prewarm(); controller.mute(); controller.answered {}
                withTimeout(3000) { entered.await() }
                assertTrue(nativeMuted)
                controller.unmute {}
                gate.complete(Unit)
                withTimeout(3000) { connected.await() }
                assertFalse(nativeMuted)
                controller.close("ended")
                withTimeout(3000) { closed.await() }
            } finally { NativeMediaRegistry.factories.remove("webrtc"); owner.cancel() }
        }
    }

}
