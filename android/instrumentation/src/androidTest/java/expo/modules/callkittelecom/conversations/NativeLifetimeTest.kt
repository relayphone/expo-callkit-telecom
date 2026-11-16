package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLifetimeTest {
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
                val controller = NativeMediaController(InstrumentationRegistry.getInstrumentation().targetContext,
                    emptyMap(), JSONObject(mapOf("media" to "webrtc")), owner, {}, { connected.complete(Unit) })
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

    @Test fun holdBeforeActivationCanResumeWithoutStartingHeldMedia() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val waiting = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val connected = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            var playing = false
            var active = false
            var activations = 0
            var muted = false
            NativeMediaRegistry.factories["webrtc"] = {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) { assertFalse(payload.containsKey("media")) }
                    override suspend fun activate() { active = true; playing = true; activations++ }
                    override suspend fun setHeld(held: Boolean) { playing = active && !held }
                    override suspend fun setMuted(value: Boolean) { muted = value }
                    override suspend fun close(reason: String) { playing = false; closed.complete(Unit) }
                }
            }
            try {
                val controller = NativeMediaController(InstrumentationRegistry.getInstrumentation().targetContext,
                    emptyMap(), JSONObject(mapOf("media" to "webrtc")), owner, {}, { connected.complete(Unit) })
                controller.prewarm(); controller.answered { waiting.complete(Unit); gate.await() }
                withTimeout(3000) { waiting.await() }
                controller.pause(); gate.complete(Unit)
                delay(50)
                assertFalse(playing); assertEquals(0, activations)
                controller.resume {}
                withTimeout(3000) { connected.await() }
                assertTrue(playing); assertEquals(1, activations)
                controller.pause(); assertFalse(playing)
                val resumeWaiting = CompletableDeferred<Unit>()
                val resumeGate = CompletableDeferred<Unit>()
                controller.resume { resumeWaiting.complete(Unit); resumeGate.await() }
                withTimeout(3000) { resumeWaiting.await() }
                controller.mute()
                resumeGate.complete(Unit)
                withTimeout(3000) { while (!playing) delay(10) }
                assertTrue(muted); assertEquals(1, activations)
                controller.pause(); assertFalse(playing)
                val unmuteWaiting = CompletableDeferred<Unit>()
                val unmuteGate = CompletableDeferred<Unit>()
                controller.resume { unmuteWaiting.complete(Unit); unmuteGate.await() }
                withTimeout(3000) { unmuteWaiting.await() }
                controller.unmute {}
                unmuteGate.complete(Unit)
                withTimeout(3000) { while (!playing || muted) delay(10) }
                assertEquals(1, activations)
                controller.close("ended")
                withTimeout(3000) { closed.await() }
            } finally { NativeMediaRegistry.factories.remove("webrtc"); owner.cancel() }
        }
    }

    @Test fun invalidatedPrejoinRetriesOnceAtAnswer() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val prepared = CompletableDeferred<Unit>()
            val connected = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val ready = java.util.concurrent.atomic.AtomicBoolean(false)
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            NativeMediaRegistry.factories["livekit"] = {
                object : NativeMediaSession {
                    override val isPrepared get() = ready.get()
                    override suspend fun prewarm(payload: Map<String, Any?>) { attempts.incrementAndGet(); ready.set(true); prepared.complete(Unit) }
                    override suspend fun activate() { assertTrue(ready.get()) }
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(muted: Boolean) {}
                    override suspend fun close(reason: String) { closed.complete(Unit) }
                }
            }
            try {
                val controller = NativeMediaController(InstrumentationRegistry.getInstrumentation().targetContext,
                    mapOf("eventId" to "invalidated-event", "serverCallId" to "invalidated-call", "media" to mapOf(
                        "provider" to "livekit", "url" to "wss://fixture.invalid", "room" to "fixture-room",
                        "identity" to "fixture-device", "token" to "fixture-not-a-jwt", "expiresAt" to System.currentTimeMillis()/1000+120)),
                    JSONObject(mapOf("media" to "livekit")), owner, { fail("Unexpected native failure") }, { connected.complete(Unit) })
                controller.prewarm(); withTimeout(3000) { prepared.await() }
                ready.set(false)
                controller.answered {}
                withTimeout(3000) { connected.await() }
                assertEquals(2, attempts.get())
                controller.close("ended"); withTimeout(3000) { closed.await() }
            } finally { NativeMediaRegistry.factories.remove("livekit"); owner.cancel() }
        }
    }

    @Test fun a2500MillisecondRetirementStillAnswersWithinFiveSeconds() = retirement(2500, true)

    @Test fun a3500MillisecondRetirementFailsWithItsReasonAndMeasurements() = retirement(3500, false)

    @Test fun anExpiredPhaseOneAnswerDoesNotStartAnotherPreparation() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Unit>()
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val previous = NativeMediaRegistry.factories.put("livekit") {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) {
                        attempts.incrementAndGet(); entered.complete(Unit); release.await()
                    }
                    override suspend fun activate() { fail("Expired Answer must not activate") }
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(muted: Boolean) {}
                    override suspend fun close(reason: String) {}
                }
            }
            val controller = NativeMediaController(InstrumentationRegistry.getInstrumentation().targetContext,
                mapOf("serverCallId" to "expired-preparation", "media" to mapOf("provider" to "livekit",
                    "expiresAt" to System.currentTimeMillis() / 1000 + 120)),
                JSONObject(mapOf("media" to "livekit")), scope, { failed.complete(Unit) }, {})
            try {
                controller.prewarm()
                withTimeout(1000) { entered.await() }
                controller.answered {}
                withTimeout(6000) { failed.await() }
                // Leave cleanup to this test so an incorrectly launched retry can actually run.
                release.complete(Unit)
                withTimeout(1000) { while (!controller.isPrewarmed) delay(5) }
                assertEquals(1, attempts.get())
            } finally {
                controller.close("ended")
                withContext(NonCancellable) { withTimeout(5000) { controller.awaitClosed() } }
                scope.cancel()
                if (previous == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previous
            }
        }
    }

    @Test fun answerPreparationFailureIsClassifiedBeforeAnsweredIsCalled() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val observed = CompletableDeferred<NativeMediaDiagnostic>()
            val previous = NativeMediaRegistry.factories.put("livekit") {
                object : NativeMediaSession {
                    private var preparationFailed: (NativePrewarmFailure) -> Unit = {}
                    override fun setPreparationFailureListener(listener: (NativePrewarmFailure) -> Unit) { preparationFailed = listener }
                    override suspend fun prewarm(payload: Map<String, Any?>) {
                        preparationFailed(NativePrewarmFailure(NativePrewarmStage.CONNECT, "IllegalStateException"))
                        throw IllegalStateException("controlled connect failure")
                    }
                    override suspend fun activate() { fail("Failed preparation must not activate") }
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(muted: Boolean) {}
                    override suspend fun close(reason: String) {}
                }
            }
            val controller = NativeMediaController(InstrumentationRegistry.getInstrumentation().targetContext,
                mapOf("serverCallId" to "early-answer-failure", "media" to mapOf("provider" to "livekit",
                    "expiresAt" to System.currentTimeMillis() / 1000 + 120)),
                JSONObject(mapOf("media" to "livekit")), scope, {}, {})
            controller.setDiagnosticListener { observed.complete(it) }
            try {
                controller.prewarmForAnswer()
                assertEquals(NativeMediaDiagnostic.Answer(NativeAnswerFailure(NativeAnswerStage.PREPARE, false)),
                    withTimeout(1000) { observed.await() })
                controller.answered {}
            } finally {
                controller.close("ended")
                withContext(NonCancellable) { withTimeout(5000) { controller.awaitClosed() } }
                scope.cancel()
                if (previous == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previous
            }
        }
    }

    private fun retirement(closeMillis: Long, shouldConnect: Boolean) = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val previousFactory = NativeMediaRegistry.factories["livekit"]
            val id = "retirement-$closeMillis-${System.nanoTime()}"
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            val prepared = CompletableDeferred<Unit>()
            val result = CompletableDeferred<Boolean>()
            var creations = 0
            var activations = 0
            NativeMediaRegistry.factories["livekit"] = {
                val old = creations++ == 0
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) { if (old) prepared.complete(Unit) }
                    override suspend fun activate() { assertFalse(old); activations++ }
                    override suspend fun setHeld(held: Boolean) {}
                    override suspend fun setMuted(muted: Boolean) {}
                    override suspend fun close(reason: String) {
                        if (old) traceNativeClose(NativeCloseStep.ROOM_RELEASE) { delay(closeMillis) }
                    }
                }
            }
            val payload = mapOf("serverCallId" to id, "media" to mapOf("provider" to "livekit",
                "expiresAt" to System.currentTimeMillis() / 1000 + 120))
            val policy = JSONObject(mapOf("media" to "livekit"))
            val old = NativeMediaController(context, payload, policy, owner, { fail("Unexpected prejoin failure") }, {})
            val fresh = NativeMediaController(context, payload, policy, owner,
                { result.complete(false) }, { result.complete(true) })
            NativeMediaSignals.observers += observer
            try {
                old.prewarm()
                withTimeout(3000) { prepared.await(); while (!old.isPrewarmed) delay(5) }
                val requested = SystemClock.elapsedRealtimeNanos()
                fresh.prewarmForAnswer(old)
                fresh.answered {}
                assertEquals(shouldConnect, withTimeout(5000) { result.await() })
                val elapsed = SystemClock.elapsedRealtimeNanos() - requested
                assertTrue(elapsed < 5_000_000_000L)
                withTimeout(1000) { while (signals.none { it.stage == NativeMediaStage.RELEASE_COMPLETED } ||
                    !shouldConnect && signals.none { it.stage == NativeMediaStage.ANSWER_FAILED }) delay(5) }
                assertEquals(if (shouldConnect) 2 else 1, creations)
                assertEquals(if (shouldConnect) 1 else 0, activations)
                val released = signals.single { it.stage == NativeMediaStage.RELEASE_COMPLETED }
                assertEquals(if (shouldConnect) 1L else 0L, released.measurements["completed"])
                assertEquals(if (shouldConnect) 0L else 1L, released.measurements["timeout"])
                val operation = released.measurements.getValue("operationId")
                for (stage in listOf(NativeMediaStage.CLOSE_REQUESTED, NativeMediaStage.CLOSE_LIFETIME_JOINED,
                    NativeMediaStage.CLOSE_SESSION_COMPLETED, NativeMediaStage.CLOSE_ROOM_RELEASED)) {
                    val signal = signals.single { it.stage == stage && it.measurements["operationId"] == operation }
                    assertTrue(signal.elapsedRealtimeNanos <= released.elapsedRealtimeNanos)
                    assertTrue(signal.measurements.getValue("durationNs") >= 0L)
                    assertTrue(signal.measurements.getValue("startedNs") > 0L)
                }
                val room = signals.single { it.stage == NativeMediaStage.CLOSE_ROOM_RELEASED }
                assertTrue(room.measurements.getValue("durationNs") >= 2_500_000_000L)
                assertEquals(if (shouldConnect) 1L else 0L, room.measurements["completed"])
                if (shouldConnect) assertTrue(signals.none { it.stage == NativeMediaStage.ANSWER_FAILED })
                else {
                    val failure = signals.single { it.stage == NativeMediaStage.ANSWER_FAILED }
                    assertEquals("PREJOIN_CLOSE_FAILED", failure.reason)
                    assertEquals(1L, failure.measurements["phase"])
                    assertEquals(0L, failure.measurements["timeout"])
                    assertTrue(failure.elapsedRealtimeNanos >= released.elapsedRealtimeNanos)
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("retirementGuardTiming", JSONObject().put("closeMillis", closeMillis)
                        .put("connected", shouldConnect).put("answerElapsedNs", elapsed.toString())
                        .put("signals", org.json.JSONArray(signals.map {
                            JSONObject().put("stage", it.stage.value).put("atNs", it.elapsedRealtimeNanos.toString())
                                .put("measurements", JSONObject(it.measurements)).put("reason", it.reason ?: JSONObject.NULL)
                        })).toString())
                })
            } finally {
                fresh.close("ended"); old.close("ended")
                withContext(NonCancellable) { withTimeout(5000) {
                    fresh.awaitClosed(); runCatching { old.awaitClosed() }
                } }
                NativeMediaSignals.observers -= observer
                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previousFactory
                owner.cancel()
            }
        }
    }
}
