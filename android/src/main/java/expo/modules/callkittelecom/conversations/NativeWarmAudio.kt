package expo.modules.callkittelecom.conversations

import android.media.AudioFormat
import android.os.Debug
import android.os.SystemClock
import io.livekit.android.audio.AudioBufferCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Observes real device reads/output and clocks the SDK's recordingless buffer pump. */
internal class NativeWarmAudio(private val serverId: String) : AudioBufferCallback,
    JavaAudioDeviceModule.AudioBufferCallback {
    private class CaptureEpoch(val id: Long, val startedNanos: Long) {
        val ready = CompletableDeferred<Unit>()
    }
    private class OutputEpoch(val id: Long, val startedNanos: Long, val released: Boolean) {
        val mutedReady = CompletableDeferred<Unit>()
        val mutedObserved = AtomicBoolean(false)
        val nonzeroObserved = AtomicBoolean(false)
    }
    private class Pacing {
        var generation = -1L
        var durationNanos = 0L
        var deadlineNanos = 0L
    }
    private data class SilentWindow(
        val threadId: Long, val firstNanos: Long, val firstCpuNanos: Long,
        var lastNanos: Long, var lastCpuNanos: Long, var count: Long, var durationNanos: Long,
    )

    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val capture = AtomicReference<CaptureEpoch?>(null)
    private val firstCaptureArmNanos = AtomicLong()
    private val physicalCapture = AtomicBoolean(false)
    private val captureBeforeAuthority = AtomicBoolean(false)
    private val pumpActive = AtomicBoolean(false)
    private val stoppedReady = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })
    private val pumpGeneration = AtomicLong()
    private val pacing = ThreadLocal.withInitial { Pacing() }
    private val parkedThread = AtomicReference<Thread?>(null)
    private val silentLock = Any()
    private var silentWindow: SilentWindow? = null
    private val playbackActive = AtomicBoolean(false)
    private val playbackReady = AtomicReference(CompletableDeferred<Unit>())
    private val output = AtomicReference(OutputEpoch(0, SystemClock.elapsedRealtimeNanos(), false))
    private val firstOutputReleaseNanos = AtomicLong()
    private val outputBeforeAuthority = AtomicBoolean(false)
    @Volatile private var outputObservationAvailable = false

    fun armCapture(): Deferred<Unit> {
        val epoch = CaptureEpoch(sequence.incrementAndGet(), SystemClock.elapsedRealtimeNanos())
        if (closed.get()) return epoch.ready.apply { cancel() }
        firstCaptureArmNanos.compareAndSet(0, epoch.startedNanos)
        capture.getAndSet(epoch)?.ready?.cancel()
        if (closed.get() && capture.compareAndSet(epoch, null)) epoch.ready.cancel()
        return epoch.ready
    }

    /** Call after ADM.setAudioRecordEnabled(true), following Core audio authorization. */
    fun publishRecordingEnabled() {
        // The pinned ADM recording flag is plain Java state. pace() acquires this write
        // before the pump's next iteration reads that flag, and wakes without a 10 ms wait.
        pumpGeneration.incrementAndGet()
        parkedThread.get()?.let { LockSupport.unpark(it) }
    }

    fun pumpStarted() {
        if (closed.get()) return
        pumpGeneration.incrementAndGet()
        if (pumpActive.compareAndSet(false, true)) {
            stoppedReady.getAndSet(CompletableDeferred()).cancel()
            emit(NativeMediaStage.CAPTURE_PUMP_STARTED)
        }
    }

    fun pumpStopped() {
        pumpGeneration.incrementAndGet()
        parkedThread.get()?.let { LockSupport.unpark(it) }
        if (pumpActive.getAndSet(false)) emit(NativeMediaStage.CAPTURE_PUMP_STOPPED)
        stopCapture(SystemClock.elapsedRealtimeNanos())
        stoppedReady.get().complete(Unit)
        flushSilentWindow()
    }

    suspend fun awaitPumpStopped() { if (pumpActive.get()) withTimeout(900) { stoppedReady.get().await() } }

    fun playbackStarted() {
        if (closed.get()) return
        if (playbackActive.compareAndSet(false, true)) emit(NativeMediaStage.PLAYBACK_STARTED)
        playbackReady.get().complete(Unit)
    }

    fun playbackStopped() {
        if (playbackActive.getAndSet(false)) emit(NativeMediaStage.PLAYBACK_STOPPED)
        val next = CompletableDeferred<Unit>()
        playbackReady.getAndSet(next).cancel()
        if (closed.get()) next.cancel()
    }

    suspend fun awaitPlaybackStarted() { check(!closed.get()); playbackReady.get().await() }

    suspend fun awaitMutedOutput() {
        check(!closed.get())
        if (outputObservationAvailable) output.get().mutedReady.await()
        else awaitPlaybackStarted()
    }

    /** Publish authorization before unmuting the ADM; output callbacks may run immediately. */
    fun releaseOutput() {
        if (closed.get()) return
        val epoch = OutputEpoch(sequence.incrementAndGet(), SystemClock.elapsedRealtimeNanos(), true)
        firstOutputReleaseNanos.compareAndSet(0, epoch.startedNanos)
        output.getAndSet(epoch).mutedReady.cancel()
        emit(NativeMediaStage.OUTPUT_RELEASED, mapOf("epoch" to epoch.id), epoch.startedNanos)
    }

    fun muteOutput() {
        if (closed.get()) return
        val epoch = OutputEpoch(sequence.incrementAndGet(), SystemClock.elapsedRealtimeNanos(), false)
        output.getAndSet(epoch).mutedReady.cancel()
    }

    fun configureOutput(builder: JavaAudioDeviceModule.Builder) {
        // This pinned SDK copies playback samples using directBuffer.array().
        outputObservationAvailable = ByteBuffer.allocateDirect(8).hasArray()
        if (outputObservationAvailable) {
            builder.setPlaybackSamplesReadyCallback { samples -> observeOutput(samples) }
        } else {
            emit(NativeMediaStage.OUTPUT_OBSERVATION_UNAVAILABLE, mapOf("directBufferHasArray" to 0L))
        }
    }

    override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int,
        bytesRead: Int, captureTimeNs: Long): Long {
        val observedNanos = SystemClock.elapsedRealtimeNanos()
        val epoch = capture.get()
        if (closed.get()) return captureTimeNs
        if (bytesRead > 0) {
            pacing.get().deadlineNanos = 0
            flushSilentWindow()
            val armedNanos = firstCaptureArmNanos.get()
            if ((armedNanos == 0L || observedNanos < armedNanos) && captureBeforeAuthority.compareAndSet(false, true))
                emit(NativeMediaStage.CAPTURE_BEFORE_AUTHORITY, observedAtNanos = observedNanos)
            if (physicalCapture.compareAndSet(false, true))
                emit(NativeMediaStage.CAPTURE_STARTED, mapOf("epoch" to (epoch?.id ?: 0L)), observedNanos)
            if (epoch != null && observedNanos >= epoch.startedNanos && capture.get() === epoch)
                epoch.ready.complete(Unit)
        } else if (bytesRead == 0) {
            if (physicalCapture.get()) stopCapture(observedNanos)
            val duration = bufferDurationNanos(buffer.capacity(), audioFormat, channelCount, sampleRate)
            if (duration > 0) {
                val cpuNanos = Debug.threadCpuTimeNanos()
                if (pace(duration, observedNanos))
                    recordSilentWindow(observedNanos, cpuNanos, SystemClock.elapsedRealtimeNanos(),
                        Debug.threadCpuTimeNanos(), duration)
            }
        }
        return captureTimeNs
    }

    private fun stopCapture(observedNanos: Long) {
        val epoch = capture.getAndSet(null)
        if (physicalCapture.getAndSet(false))
            emit(NativeMediaStage.CAPTURE_STOPPED, mapOf("epoch" to (epoch?.id ?: 0L)), observedNanos)
        epoch?.ready?.cancel()
    }

    private fun bufferDurationNanos(capacity: Int, format: Int, channels: Int, sampleRate: Int): Long {
        val bytesPerSample = when (format) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_DEFAULT, AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> return 0
        }
        if (capacity <= 0 || channels <= 0 || sampleRate <= 0) return 0
        return capacity.toLong() * 1_000_000_000L / bytesPerSample / channels / sampleRate
    }

    private fun pace(duration: Long, observedNanos: Long): Boolean {
        val state = pacing.get()
        val generation = pumpGeneration.get()
        if (state.generation != generation || state.durationNanos != duration || state.deadlineNanos == 0L ||
            observedNanos - state.deadlineNanos > duration) {
            state.deadlineNanos = observedNanos
        }
        state.generation = generation
        state.durationNanos = duration
        state.deadlineNanos += duration
        val thread = Thread.currentThread()
        parkedThread.set(thread)
        try {
            while (!closed.get() && !thread.isInterrupted && pumpGeneration.get() == generation) {
                val remaining = state.deadlineNanos - SystemClock.elapsedRealtimeNanos()
                if (remaining <= 0) return true
                LockSupport.parkNanos(this, minOf(remaining, 10_000_000L))
            }
            return false
        } finally { parkedThread.compareAndSet(thread, null) }
    }

    private fun recordSilentWindow(startNanos: Long, startCpuNanos: Long, endNanos: Long,
        endCpuNanos: Long, duration: Long) {
        val threadId = Thread.currentThread().id
        var previous: SilentWindow? = null
        synchronized(silentLock) {
            if (closed.get()) return
            var window = silentWindow
            if (window == null || window.threadId != threadId) {
                previous = window
                window = SilentWindow(threadId, startNanos, startCpuNanos, endNanos, endCpuNanos, 0, 0)
                silentWindow = window
            }
            window.lastNanos = endNanos
            window.lastCpuNanos = endCpuNanos
            window.count++
            window.durationNanos += duration
        }
        previous?.let { emitSilentWindow(it) }
    }

    fun flushSilentWindow() {
        val window = synchronized(silentLock) { silentWindow.also { silentWindow = null } }
        window?.let { emitSilentWindow(it) }
    }

    private fun emitSilentWindow(window: SilentWindow) {
        if (window.count < 2) return
        emit(NativeMediaStage.SILENT_BUFFER_WINDOW, mapOf("count" to window.count,
            "elapsedNs" to (window.lastNanos - window.firstNanos).coerceAtLeast(0),
            "threadCpuNs" to (window.lastCpuNanos - window.firstCpuNanos).coerceAtLeast(0),
            "durationNs" to window.durationNanos), window.lastNanos)
    }

    private fun observeOutput(samples: JavaAudioDeviceModule.AudioSamples) {
        val observedNanos = SystemClock.elapsedRealtimeNanos()
        val epoch = output.get()
        if (closed.get() || samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return
        val pcm = ByteBuffer.wrap(samples.data).order(ByteOrder.nativeOrder())
        var peak = 0
        while (pcm.remaining() >= 2) peak = maxOf(peak, kotlin.math.abs(pcm.short.toInt()))
        if (closed.get()) return
        val releasedNanos = firstOutputReleaseNanos.get()
        if (peak > 0 && (releasedNanos == 0L || observedNanos < releasedNanos) &&
            outputBeforeAuthority.compareAndSet(false, true))
            emit(NativeMediaStage.OUTPUT_BEFORE_AUTHORITY, mapOf("peak" to peak.toLong()), observedNanos)
        if (output.get() !== epoch || observedNanos < epoch.startedNanos) return
        val measurements = mapOf("epoch" to epoch.id, "peak" to peak.toLong(),
            "sampleRate" to samples.sampleRate.toLong(), "channels" to samples.channelCount.toLong())
        if (peak == 0 && epoch.mutedObserved.compareAndSet(false, true)) {
            emit(NativeMediaStage.OUTPUT_MUTED, measurements, observedNanos)
            epoch.mutedReady.complete(Unit)
        } else if (peak > 0 && epoch.released && epoch.nonzeroObserved.compareAndSet(false, true)) {
            emit(NativeMediaStage.OUTPUT_FIRST_NONZERO, measurements, observedNanos)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        capture.getAndSet(null)?.ready?.cancel()
        stoppedReady.get().cancel()
        output.get().mutedReady.cancel()
        playbackReady.get().cancel()
        pumpGeneration.incrementAndGet()
        parkedThread.get()?.let { LockSupport.unpark(it) }
        flushSilentWindow()
    }

    private fun emit(stage: NativeMediaStage, measurements: Map<String, Long> = emptyMap(),
        observedAtNanos: Long = SystemClock.elapsedRealtimeNanos()) =
        NativeMediaSignals.emit(serverId, stage, measurements, observedAtNanos)
}
