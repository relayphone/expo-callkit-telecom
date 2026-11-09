package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDecodedAudioReadinessTest {
    private fun pcm(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    @Test fun silenceSubthresholdAndUnsupportedFormatsStayPending() = runBlocking {
        val readiness = NativeDecodedAudioReadiness()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { readiness.awaitDecoded() }
        try {
            for (data in listOf(pcm(0), pcm(127, -127), ByteBuffer.allocate(0), ByteBuffer.allocate(1))) {
                readiness.onData(data, 16, 48000, 1, data.remaining() / 2, 0)
                yield()
                assertFalse(waiting.isCompleted)
            }
            for (bits in listOf(8, 24, 32)) {
                readiness.onData(pcm(512), bits, 48000, 1, 1, 0)
                yield()
                assertFalse(waiting.isCompleted)
            }
            readiness.onData(pcm(128), 16, 48000, 1, 1, 0)
            withTimeout(1000) { waiting.await() }
        } finally { readiness.close() }
    }

    @Test fun signedThresholdAndMinimumShortReleaseReadiness() = runBlocking {
        for (sample in listOf(128, -128, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())) {
            val readiness = NativeDecodedAudioReadiness()
            try {
                readiness.onData(pcm(sample), 16, 48000, 1, 1, Long.MAX_VALUE)
                withTimeout(1000) { readiness.awaitDecoded() }
            } finally { readiness.close() }
        }
    }

    @Test fun readsOnlyTheNativeEndianBufferWindowWithoutConsumingIt() = runBlocking {
        val readiness = NativeDecodedAudioReadiness()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { readiness.awaitDecoded() }
        val data = pcm(512, 0, 127, 512).apply {
            position(2); limit(6)
            order(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        }
        val order = data.order()
        try {
            readiness.onData(data, 16, 48000, 1, 2, 0)
            yield()
            assertFalse(waiting.isCompleted)
            assertEquals(2, data.position())
            assertEquals(6, data.limit())
            assertEquals(order, data.order())
            readiness.onData(pcm(-128), 16, 48000, 1, 1, 0)
            withTimeout(1000) { waiting.await() }
        } finally { readiness.close() }
    }

    @Test fun oneDecodedFrameReleasesAllWaitersAndRemainsReady() = runBlocking {
        val readiness = NativeDecodedAudioReadiness()
        val waiting = List(3) { async(start = CoroutineStart.UNDISPATCHED) { readiness.awaitDecoded() } }
        try {
            assertTrue(waiting.none { it.isCompleted })
            readiness.onData(pcm(512), 16, 48000, 1, 1, 0)
            withTimeout(1000) { waiting.awaitAll(); readiness.awaitDecoded() }
            readiness.onData(pcm(0), 16, 48000, 1, 1, 0)
            withTimeout(1000) { readiness.awaitDecoded() }
        } finally { readiness.close() }
    }

    @Test fun closeCancelsPendingAndFutureWaitersDespiteLatePcm() = runBlocking {
        val readiness = NativeDecodedAudioReadiness()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { readiness.awaitDecoded() } }
        readiness.close()
        readiness.close()
        readiness.onData(pcm(512), 16, 48000, 1, 1, 0)
        val future = async(start = CoroutineStart.UNDISPATCHED) { runCatching { readiness.awaitDecoded() } }
        withTimeout(1000) {
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            assertTrue(future.await().exceptionOrNull() is CancellationException)
        }
    }

    @Test fun cancellingOneWaiterKeepsOtherWaitersAndTheGateAlive() = runBlocking {
        val readiness = NativeDecodedAudioReadiness()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { readiness.awaitDecoded() }
        val remaining = async(start = CoroutineStart.UNDISPATCHED) { readiness.awaitDecoded() }
        try {
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
            assertFalse(remaining.isCompleted)
            readiness.onData(pcm(128), 16, 48000, 1, 1, 0)
            withTimeout(1000) { remaining.await(); readiness.awaitDecoded() }
        } finally { readiness.close() }
    }

    @Test fun retiredReadinessAndMeasurementSinksCannotReleaseAnotherGeneration() = runBlocking {
        val retired = NativeDecodedAudioReadiness()
        val current = NativeDecodedAudioReadiness()
        val probe = NativePcmProbe("independent-decoded-readiness")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { current.awaitDecoded() }
        try {
            retired.close()
            retired.onData(pcm(512), 16, 48000, 1, 1, 0)
            probe.decoderSink.onData(pcm(512), 16, 48000, 1, 1, 0)
            probe.arm()
            probe.answerSink.onData(pcm(512), 16, 48000, 1, 1, 0)
            yield()
            assertFalse(waiting.isCompleted)
            probe.close()
            current.onData(pcm(512), 16, 48000, 1, 1, 0)
            withTimeout(1000) { waiting.await() }
        } finally { retired.close(); current.close(); probe.close() }
    }
}
