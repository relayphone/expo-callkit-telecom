package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePcmProbeTest {
    @Test fun decoderReadinessCannotReusePreanswerOrPreviousGenerationPcm() = runBlocking {
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val observer = NativeMediaObserver { if (it.serverCallId == "pcm-probe") signals += it }
        val probe = NativePcmProbe("pcm-probe")
        val data = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply {
            repeat(4) { putShort(512.toShort()) }; flip()
        }
        fun answers() = signals.count { it.stage == NativeMediaStage.FIRST_AUDIO }
        suspend fun count(expected: Int) = withTimeout(1000) { while (answers() != expected) delay(5) }
        NativeMediaSignals.observers += observer
        try {
            probe.decoderSink.onData(data, 16, 48000, 1, 4, 0)
            probe.answerSink.onData(data, 16, 48000, 1, 4, 0)
            withTimeout(1000) { while (signals.none { it.stage == NativeMediaStage.DECODER_FIRST_PCM }) delay(5) }
            assertEquals(0, answers())
            probe.arm()
            probe.decoderSink.onData(data, 16, 48000, 1, 4, 0)
            withContext(Dispatchers.Main) { }; assertEquals(0, answers())
            probe.answerSink.onData(data, 16, 48000, 1, 4, 0)
            count(1)
            probe.disarm()
            probe.answerSink.onData(data, 16, 48000, 1, 4, 0)
            withContext(Dispatchers.Main) { }; assertEquals(1, answers())
            probe.arm()
            probe.answerSink.onData(data, 16, 48000, 1, 4, 0)
            count(2)
            val audio = signals.filter { it.stage == NativeMediaStage.FIRST_AUDIO }
            assertTrue(audio[1].elapsedRealtimeNanos >= audio[0].elapsedRealtimeNanos)
            assertEquals(1, signals.count { it.stage == NativeMediaStage.DECODER_FIRST_PCM })
            probe.close()
            probe.answerSink.onData(data, 16, 48000, 1, 4, 0)
            withContext(Dispatchers.Main) { }; assertEquals(2, answers())
        } finally { probe.close(); NativeMediaSignals.observers -= observer }
    }
}
