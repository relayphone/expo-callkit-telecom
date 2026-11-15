package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMuteTimingTest {
    @Test fun serialTimeoutIsDistinguishedFromSessionWork() = runBlocking {
        withContext(Dispatchers.Main) {
            val fixture = Fixture()
            try {
                fixture.start()
                fixture.blockResume = true
                fixture.controller.resume {}
                withTimeout(3000) { fixture.resumeEntered.await() }
                val failure = runCatching { fixture.controller.mute() }.exceptionOrNull()
                assertTrue(failure is TimeoutCancellationException)
                fixture.awaitExit(NativeMediaStage.MUTE_SERIAL_EXITED)
                assertEquals(listOf(NativeMediaStage.MUTE_SERIAL_ENTERED, NativeMediaStage.MUTE_SERIAL_EXITED),
                    fixture.signals.map { it.stage })
                assertEquals(0L, fixture.signals.last().measurements["completed"])
                assertEquals(0, fixture.muteCalls)
            } finally { fixture.close() }
        }
    }

    @Test fun sessionTimeoutRecordsNormalAcquisitionAndCancelledSession() = runBlocking {
        withContext(Dispatchers.Main) {
            val fixture = Fixture()
            try {
                fixture.start()
                val failure = runCatching { fixture.controller.mute() }.exceptionOrNull()
                assertTrue(failure is TimeoutCancellationException)
                fixture.awaitExit(NativeMediaStage.MUTE_SESSION_EXITED)
                assertEquals(listOf(NativeMediaStage.MUTE_SERIAL_ENTERED, NativeMediaStage.MUTE_SERIAL_EXITED,
                    NativeMediaStage.MUTE_SESSION_ENTERED, NativeMediaStage.MUTE_SESSION_EXITED), fixture.signals.map { it.stage })
                assertEquals(1L, fixture.signals[1].measurements["completed"])
                assertEquals(0L, fixture.signals.last().measurements["completed"])
                assertEquals(1, fixture.muteCalls)
                assertEquals(1, fixture.signals.map { it.measurements["operationId"] }.distinct().size)
                assertTrue(fixture.signals.zipWithNext().all { (a, b) -> a.elapsedRealtimeNanos <= b.elapsedRealtimeNanos })
            } finally { fixture.close() }
        }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val connected = CompletableDeferred<Unit>()
        private val closed = CompletableDeferred<Unit>()
        private val previous = NativeMediaRegistry.factories["webrtc"]
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        private val id = "mute-timing-${System.nanoTime()}"
        private val observer = NativeMediaObserver { signal ->
            if (signal.serverCallId == id && NativeMuteStep.entries.any {
                signal.stage == it.entered || signal.stage == it.exited
            }) signals += signal
        }
        val resumeEntered = CompletableDeferred<Unit>()
        var blockResume = false
        var muteCalls = 0
        val controller = NativeMediaController(context, mapOf("serverCallId" to id),
            JSONObject(mapOf("media" to "webrtc")), scope, {}, { connected.complete(Unit) })

        suspend fun start() {
            NativeMediaSignals.observers += observer
            NativeMediaRegistry.factories["webrtc"] = {
                object : NativeMediaSession {
                    override suspend fun prewarm(payload: Map<String, Any?>) {}
                    override suspend fun activate() {}
                    override suspend fun setHeld(held: Boolean) {
                        if (blockResume && !held) { resumeEntered.complete(Unit); awaitCancellation() }
                    }
                    override suspend fun setMuted(muted: Boolean) {
                        if (muted) { muteCalls++; awaitCancellation() }
                    }
                    override suspend fun close(reason: String) { closed.complete(Unit) }
                }
            }
            controller.prewarm(); controller.answered {}
            withTimeout(3000) { connected.await() }
        }
        suspend fun awaitExit(stage: NativeMediaStage) = withTimeout(3000) {
            while (signals.none { it.stage == stage }) delay(10)
        }
        suspend fun close() {
            try { controller.close("test complete"); withTimeout(3000) { closed.await() } }
            finally {
                scope.cancel(); NativeMediaSignals.observers -= observer
                if (previous == null) NativeMediaRegistry.factories.remove("webrtc")
                else NativeMediaRegistry.factories["webrtc"] = previous
            }
        }
    }
}
