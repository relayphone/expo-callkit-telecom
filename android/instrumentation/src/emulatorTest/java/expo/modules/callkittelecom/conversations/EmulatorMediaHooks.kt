package expo.modules.callkittelecom.conversations

import android.content.Context
import android.media.AudioFormat
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.room.track.LocalTrackPublication
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Software PCM qualification only. This implementation exists solely in the emulatorTest source set. */
internal class EmulatorMediaHooks(context: Context, tapEnabled: Boolean = true) : NativeAudioHooks {
    data class Observation(val blocks: Long, val frames: Long, val nonzero: Long, val tones: Long)
    private class Window {
        val blocks = AtomicLong()
        val frames = AtomicLong()
        val nonzero = AtomicLong()
        val tones = AtomicLong()
        fun snapshot() = Observation(blocks.get(), frames.get(), nonzero.get(), tones.get())
    }
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<MediaHookFailure?>(null)
    private val decoded = AtomicReference(Window())
    private val output = AtomicReference(Window())
    private val sourceCursor = AtomicLong()
    val injectedFrames = AtomicLong()
    val recordinglessCallbacks = AtomicLong()
    private var publication: LocalTrackPublication? = null
    val directArrayVerified: Boolean

    init {
        MediaTestRuntime.activate(context)
        val buffer = ByteBuffer.allocateDirect(16)
        directArrayVerified = buffer.hasArray() && runCatching {
            buffer.put(0, 0x5a.toByte())
            buffer.array()[buffer.arrayOffset()] == buffer.get(0)
        }.getOrDefault(false)
        if (!tapEnabled || !directArrayVerified) throw MediaHookUnavailable(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE)
    }

    fun beginPhase() { decoded.set(Window()); output.set(Window()) }
    fun decoded(): Observation { available(); return decoded.get().snapshot() }
    fun output(): Observation { available(); return output.get().snapshot() }
    private fun available() { failure.get()?.let { throw MediaHookUnavailable(it) }; check(!closed.get()) }

    override val decodedSink = object : AudioTrackSink {
        override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, capturedAt: Long) {
            if (bits != 16) { failure.set(MediaHookFailure.UNSUPPORTED_PCM); return }
            observe(decoded.get(), data.duplicate().order(ByteOrder.nativeOrder()), rate, channels)
        }
    }

    override fun capture(pacing: AudioBufferCallback) = object : AudioBufferCallback {
        override fun onBuffer(buffer: ByteBuffer, format: Int, channels: Int, rate: Int, bytesRead: Int, timestamp: Long): Long {
            // Always retain the production recordingless pump's pacing and lifecycle observations.
            val result = pacing.onBuffer(buffer, format, channels, rate, bytesRead, timestamp)
            if (!closed.get()) {
                if (format != AudioFormat.ENCODING_PCM_16BIT || channels !in 1..2 || rate <= 0) {
                    failure.set(MediaHookFailure.UNSUPPORTED_PCM)
                } else {
                    val pcm = buffer.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }
                    val frames = pcm.remaining() / 2 / channels
                    val first = sourceCursor.getAndAdd(frames.toLong())
                    for (frame in 0 until frames) {
                        val index = first + frame
                        val frequency = frequencies[((index * 1000 / rate / 80) % frequencies.size).toInt()]
                        val sample = (6000 * sin(2 * PI * frequency * index / rate)).toInt().toShort()
                        repeat(channels) { pcm.putShort(sample) }
                    }
                    injectedFrames.addAndGet(frames.toLong())
                    if (bytesRead == 0) recordinglessCallbacks.incrementAndGet()
                }
            }
            return result
        }
    }

    override fun configureOutput(builder: JavaAudioDeviceModule.Builder,
        observer: JavaAudioDeviceModule.PlaybackSamplesReadyCallback) {
        available()
        builder.setPlaybackSamplesReadyCallback { samples ->
            observer.onWebRtcAudioTrackSamplesReady(samples)
            if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) failure.set(MediaHookFailure.UNSUPPORTED_PCM)
            else observe(output.get(), ByteBuffer.wrap(samples.data).order(ByteOrder.nativeOrder()), samples.sampleRate, samples.channelCount)
        }
    }

    override fun published(publication: LocalTrackPublication) { this.publication = publication }
    fun publicationMuted(muted: Boolean) { available(); checkNotNull(publication).muted = muted }

    private fun observe(window: Window, pcm: ByteBuffer, rate: Int, channels: Int) {
        if (closed.get()) return
        if (rate <= 0 || channels !in 1..2 || pcm.remaining() % (2 * channels) != 0) {
            failure.set(MediaHookFailure.UNSUPPORTED_PCM); return
        }
        val count = pcm.remaining() / 2 / channels
        var peak = 0
        var energy = 0.0
        var real = 0.0
        var imaginary = 0.0
        repeat(count) { frame ->
            val value = pcm.short.toInt()
            repeat(channels - 1) { pcm.short }
            peak = maxOf(peak, abs(value))
            energy += value.toDouble() * value
            val angle = 2 * PI * 440 * frame / rate
            real += value * cos(angle); imaginary += value * sin(angle)
        }
        if (closed.get()) return
        window.frames.addAndGet(count.toLong())
        if (peak > 0) window.nonzero.incrementAndGet()
        if (peak >= 128 && energy > 0 && (real * real + imaginary * imaginary) / (energy * count) > 0.15)
            window.tones.incrementAndGet()
        window.blocks.incrementAndGet()
    }

    override fun close() { closed.set(true) }

    private companion object {
        // Six distinct ordered symbols; the far end must recognize the entire sequence.
        val frequencies = doubleArrayOf(1000.0, 1800.0, 1200.0, 2200.0, 1400.0, 2000.0)
    }
}
