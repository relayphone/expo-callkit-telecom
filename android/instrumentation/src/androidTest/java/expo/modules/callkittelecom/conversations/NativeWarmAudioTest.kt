package expo.modules.callkittelecom.conversations

import android.media.AudioFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeWarmAudioTest {
    @Test fun recordinglessBuffersFollowTheirAudioDurationAndPreserveCaptureTimestamps() = runBlocking {
        withAudio("warm-audio-pacing") {
            audio.pumpStarted()
            // Each fixture represents 10 ms at 48 kHz, including different sample widths/channels.
            val formats = listOf(
                Triple(AudioFormat.ENCODING_PCM_8BIT, 1, 480),
                Triple(AudioFormat.ENCODING_PCM_16BIT, 2, 1920),
                Triple(AudioFormat.ENCODING_PCM_FLOAT, 1, 1920),
            )
            for ((format, channels, capacity) in formats) {
                val buffer = ByteBuffer.allocateDirect(capacity)
                val started = SystemClock.elapsedRealtimeNanos()
                repeat(12) {
                    assertEquals(CAPTURE_TIMESTAMP,
                        audio.onBuffer(buffer, format, channels, SAMPLE_RATE, 0, CAPTURE_TIMESTAMP))
                }
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                assertTrue("Twelve 10 ms recordingless buffers returned without audio pacing: $elapsed ns",
                    elapsed >= 90_000_000L)
                assertTrue("A 120 ms audio window stalled for more than two seconds: $elapsed ns",
                    elapsed < 2_000_000_000L)
                audio.flushSilentWindow()
            }
            audio.pumpStopped()
            drainSignals()
            val windows = signals.filter { it.stage == NativeMediaStage.SILENT_BUFFER_WINDOW }
            assertEquals(3, windows.size)
            windows.forEach {
                assertEquals(12L, it.measurements["count"])
                assertEquals(120_000_000L, it.measurements["durationNs"])
            }
            assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_STARTED })
            assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_BEFORE_AUTHORITY })
        }
    }

    @Test fun positiveReadBeforeAuthorizationIsReportedWithoutChangingItsTimestamp() = runBlocking {
        withAudio("warm-audio-before-authority") {
            audio.pumpStarted()
            repeat(2) { assertEquals(CAPTURE_TIMESTAMP, positiveRead(audio)) }
            audio.pumpStopped()
            drainSignals()
            assertEquals(1, signals.count { it.stage == NativeMediaStage.CAPTURE_BEFORE_AUTHORITY })
            assertEquals(1, signals.count { it.stage == NativeMediaStage.CAPTURE_STARTED })
            assertEquals(1, signals.count { it.stage == NativeMediaStage.CAPTURE_STOPPED })
        }
    }

    @Test fun recordingReleaseWakesTheSilentPumpAndOnlyAPositiveReadCompletesCaptureReadiness() = runBlocking {
        withAudio("warm-audio-recording-release") {
            audio.pumpStarted()
            ParkedPump(audio, positiveAfterSilence = true).use { pump ->
                pump.awaitParked()
                val ready = audio.armCapture()
                assertFalse(ready.isCompleted)
                // Represents the caller enabling the ADM between arm and publication.
                audio.publishRecordingEnabled()
                pump.awaitFinished()
                withTimeout(2000) { ready.await() }
                assertEquals(listOf(CAPTURE_TIMESTAMP, CAPTURE_TIMESTAMP), pump.timestamps.toList())
            }
            audio.pumpStopped()
            drainSignals()
            assertEquals(1, signals.count { it.stage == NativeMediaStage.CAPTURE_STARTED })
            assertEquals(1, signals.count { it.stage == NativeMediaStage.CAPTURE_STOPPED })
            assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_BEFORE_AUTHORITY })
        }
    }

    @Test fun replacingOrStoppingACaptureEpochCannotReuseEarlierReadiness() = runBlocking {
        withAudio("warm-audio-capture-epochs") {
            audio.pumpStarted()
            val replaced = audio.armCapture()
            val first = audio.armCapture()
            assertTrue(replaced.isCancelled)
            assertFalse(first.isCompleted)
            audio.publishRecordingEnabled()
            positiveRead(audio)
            withTimeout(2000) { first.await() }
            audio.pumpStopped()

            audio.pumpStarted()
            val second = audio.armCapture()
            assertFalse("The previous positive read must not satisfy a new epoch", second.isCompleted)
            silentRead(audio)
            assertFalse("A recordingless callback must not satisfy capture readiness", second.isCompleted)
            audio.publishRecordingEnabled()
            positiveRead(audio)
            withTimeout(2000) { second.await() }
            audio.pumpStopped()
            drainSignals()
            val starts = signals.filter { it.stage == NativeMediaStage.CAPTURE_STARTED }
            val stops = signals.filter { it.stage == NativeMediaStage.CAPTURE_STOPPED }
            assertEquals(2, starts.size)
            assertEquals(2, stops.size)
            assertNotEquals(starts[0].measurements["epoch"], starts[1].measurements["epoch"])
            assertEquals(starts.map { it.measurements["epoch"] }, stops.map { it.measurements["epoch"] })
        }
    }

    @Test fun pumpStopCancelsPendingCaptureWithoutInventingPhysicalCaptureStops() = runBlocking {
        withAudio("warm-audio-pump-stop") {
            audio.pumpStarted()
            val ready = audio.armCapture()
            ParkedPump(audio).use { pump ->
                pump.awaitParked()
                audio.pumpStopped()
                pump.awaitFinished()
            }
            audio.pumpStopped()
            withTimeout(2000) { audio.awaitPumpStopped() }
            assertTrue(ready.isCancelled)
            drainSignals()
            assertEquals(1, signals.count { it.stage == NativeMediaStage.CAPTURE_PUMP_STARTED })
            assertEquals(1, signals.count { it.stage == NativeMediaStage.CAPTURE_PUMP_STOPPED })
            assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_STARTED })
            assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_STOPPED })
        }
    }

    @Test fun closingTheHelperUnblocksAParkedPumpAndCancelsPendingCapture() = runBlocking {
        withAudio("warm-audio-close") {
            audio.pumpStarted()
            val ready = audio.armCapture()
            ParkedPump(audio).use { pump ->
                pump.awaitParked()
                audio.close()
                pump.awaitFinished()
                assertEquals(listOf(CAPTURE_TIMESTAMP), pump.timestamps.toList())
            }
            assertTrue(ready.isCancelled)
            assertTrue(audio.armCapture().isCancelled)
            assertEquals(CAPTURE_TIMESTAMP, positiveRead(audio))
            drainSignals()
            assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_STARTED })
        }
    }

    @Test fun playbackReadinessRequiresAFreshDeviceStartAndOutputReleaseDoesNotSatisfyIt() = runBlocking {
        withAudio("warm-audio-playback-epochs") {
            val first = async(start = CoroutineStart.UNDISPATCHED) { audio.awaitPlaybackStarted() }
            assertFalse(first.isCompleted)
            audio.playbackStarted()
            withTimeout(2000) { first.await() }
            audio.playbackStopped()

            val stopped = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { audio.awaitPlaybackStarted() }
            }
            assertFalse(stopped.isCompleted)
            audio.playbackStopped()
            assertTrue(withTimeout(2000) { stopped.await() }.exceptionOrNull() is CancellationException)

            val resumed = async(start = CoroutineStart.UNDISPATCHED) { audio.awaitPlaybackStarted() }
            audio.muteOutput()
            audio.releaseOutput()
            assertFalse("Authorizing output is not evidence of a running playback device", resumed.isCompleted)
            audio.playbackStarted()
            withTimeout(2000) { resumed.await() }
            audio.playbackStopped()
            drainSignals()
            assertEquals(2, signals.count { it.stage == NativeMediaStage.PLAYBACK_STARTED })
            assertEquals(2, signals.count { it.stage == NativeMediaStage.PLAYBACK_STOPPED })
            assertEquals(1, signals.count { it.stage == NativeMediaStage.OUTPUT_RELEASED })
            assertFalse(signals.any { it.stage == NativeMediaStage.OUTPUT_FIRST_NONZERO })
            val closing = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { audio.awaitPlaybackStarted() }
            }
            audio.close()
            assertTrue(withTimeout(2000) { closing.await() }.exceptionOrNull() is CancellationException)
        }
    }

    private class Observations(serverId: String) : Closeable {
        val audio = NativeWarmAudio(serverId)
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        private val observer = NativeMediaObserver { if (it.serverCallId == serverId) signals += it }
        init { NativeMediaSignals.observers += observer }
        suspend fun drainSignals() { withContext(Dispatchers.Main) {} }
        override fun close() { audio.close(); NativeMediaSignals.observers -= observer }
    }

    /** A device callback blocked by a five-second silent buffer, rather than by a test latch. */
    private class ParkedPump(audio: NativeWarmAudio, positiveAfterSilence: Boolean = false) : Closeable {
        val timestamps = CopyOnWriteArrayList<Long>()
        private val failure = AtomicReference<Throwable?>()
        private val finished = CountDownLatch(1)
        private val thread = Thread({
            try {
                timestamps += audio.onBuffer(ByteBuffer.allocateDirect(480_000),
                    AudioFormat.ENCODING_PCM_16BIT, 1, SAMPLE_RATE, 0, CAPTURE_TIMESTAMP)
                if (positiveAfterSilence) timestamps += positiveRead(audio)
            } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }, "native-warm-audio-test-pump").apply { start() }

        suspend fun awaitParked() = withTimeout(2000) {
            while (thread.state != Thread.State.TIMED_WAITING) {
                failure.get()?.let { throw AssertionError("Fake audio pump failed", it) }
                check(thread.isAlive) { "Fake audio pump exited before parking" }
                delay(5)
            }
        }

        fun awaitFinished() {
            assertTrue("The silent pump did not wake", finished.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Fake audio pump failed", it) }
        }

        override fun close() { thread.interrupt(); thread.join(2000) }
    }

    private suspend fun withAudio(serverId: String, block: suspend Observations.() -> Unit) {
        Observations(serverId).use { it.block() }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CAPTURE_TIMESTAMP = 1_234_567_890_123L
        private fun positiveRead(audio: NativeWarmAudio): Long = audio.onBuffer(ByteBuffer.allocateDirect(960),
            AudioFormat.ENCODING_PCM_16BIT, 1, SAMPLE_RATE, 960, CAPTURE_TIMESTAMP)
        private fun silentRead(audio: NativeWarmAudio): Long = audio.onBuffer(ByteBuffer.allocateDirect(960),
            AudioFormat.ENCODING_PCM_16BIT, 1, SAMPLE_RATE, 0, CAPTURE_TIMESTAMP)
    }
}
