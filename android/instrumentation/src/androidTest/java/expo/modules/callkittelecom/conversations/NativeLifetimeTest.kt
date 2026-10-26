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

}
