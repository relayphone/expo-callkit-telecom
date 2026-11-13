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
import kotlin.math.roundToInt
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Software PCM qualification only. This implementation exists solely in the emulatorTest source set. */
internal class EmulatorMediaHooks(context: Context, markerId: String, tapEnabled: Boolean = true,
    private val requestedRate: Int? = null, private val emission: Boolean = true) : NativeAudioHooks {
    // Match SDK testing/marker.ts: UTF-8 FNV-1a, four sync slots, low sixteen hash bits.
    private val frequencies = run {
        var hash = 2166136261L
        for (byte in markerId.toByteArray(Charsets.UTF_8)) hash = ((hash xor (byte.toLong() and 255)) * 16777619L) and 0xffffffffL
        val tones = doubleArrayOf(650.0, 950.0, 1350.0, 1850.0)
        tones + DoubleArray(8) { tones[((hash ushr (it * 2)) and 3).toInt()] }
    }
    enum class Stimulus { MARKER, ZERO, WRONG }
    @Volatile var stimulus = Stimulus.MARKER
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
    private val observingOutput = AtomicBoolean(true)
    private val sourceCursor = AtomicLong()
    val injectedFrames = AtomicLong()
    val generatedWords = AtomicLong()
    @Volatile var captureSampleRate = 0
        private set
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
    fun observeOutput(enabled: Boolean) { observingOutput.set(enabled) }
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
                if (format != AudioFormat.ENCODING_PCM_16BIT || channels !in 1..2 || rate !in 8000..96000 || rate % 50 != 0 ||
                    buffer.capacity() % (2 * channels) != 0) {
                    failure.set(MediaHookFailure.UNSUPPORTED_PCM)
                } else {
                    captureSampleRate = rate
                    val pcm = buffer.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }
                    val frames = pcm.remaining() / 2 / channels
                    val first = sourceCursor.getAndAdd(frames.toLong())
                    val stimulus = this@EmulatorMediaHooks.stimulus
                    for (frame in 0 until frames) {
                        val index = first + frame
                        val slotFrames = rate / 50 * 3
                        val pre = (rate * 3 / 20.0).roundToInt()
                        val post = pre + slotFrames * frequencies.size
                        val cycle = rate / 50 * 51
                        val position = index % if (emission) cycle else slotFrames * frequencies.size
                        val inMarker = !emission || (position >= pre && position < post)
                        val markerPosition = if (emission) position - pre else position
                        val local = if (inMarker) markerPosition % slotFrames else if (position < pre) position else position - post
                        val frequency = if (inMarker) frequencies[(markerPosition / slotFrames).toInt()] else 400.0
                        val edge = if (inMarker) minOf(local, slotFrames - local) else if (position < pre) local else cycle - position
                        val fade = minOf(1.0, edge / (rate / 200.0))
                        val envelope = 0.5 * (1 - cos(PI * fade))
                        // Float32 normalization and PCM16 conversion match the SDK's host source.
                        val normalized = (if (stimulus == Stimulus.WRONG) 0.2 * sin(2 * PI * 440 * index / rate)
                            else 0.2 * envelope * sin(2 * PI * frequency * local / rate)).toFloat()
                        val sample = if (stimulus == Stimulus.ZERO) 0.toShort() else
                            (normalized.toDouble() * 32767).roundToInt().toShort()
                        repeat(channels) { pcm.putShort(sample) }
                    }
                    injectedFrames.addAndGet(frames.toLong())
                    if (stimulus == Stimulus.MARKER) {
                        val wordFrames = if (emission) rate.toLong() / 50 * 51 else rate.toLong() * SYMBOL_MILLIS * frequencies.size / 1000
                        generatedWords.addAndGet((first + frames) / wordFrames - first / wordFrames)
                    }
                    if (bytesRead == 0) recordinglessCallbacks.incrementAndGet()
                }
            }
            return result
        }
    }

    override fun configureOutput(builder: JavaAudioDeviceModule.Builder,
        observer: JavaAudioDeviceModule.PlaybackSamplesReadyCallback) {
        available()
        requestedRate?.let { require(it == 8000 || it == 48000); builder.setSampleRate(it) }
        builder.setPlaybackSamplesReadyCallback { samples ->
            observer.onWebRtcAudioTrackSamplesReady(samples)
            if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) failure.set(MediaHookFailure.UNSUPPORTED_PCM)
            else if (observingOutput.get()) observe(output.get(),
                ByteBuffer.wrap(samples.data).order(ByteOrder.nativeOrder()), samples.sampleRate, samples.channelCount)
        }
    }

    override fun published(publication: LocalTrackPublication) { this.publication = publication }
    fun publicationIdentity() = checkNotNull(publication)

    private fun observe(window: Window, pcm: ByteBuffer, rate: Int, channels: Int) {
        if (closed.get()) return
        if (rate <= 0 || channels !in 1..2 || pcm.remaining() % (2 * channels) != 0) {
            failure.set(MediaHookFailure.UNSUPPORTED_PCM); return
        }
        val count = pcm.remaining() / 2 / channels
        var peak = 0
        var energy = 0.0
        val coefficients = doubleArrayOf(400.0, 650.0, 950.0, 1350.0, 1850.0).map { 2 * cos(2 * PI * it / rate) }
        val previous = DoubleArray(coefficients.size)
        val before = DoubleArray(coefficients.size)
        repeat(count) { frame ->
            val value = pcm.short.toInt()
            peak = maxOf(peak, abs(value))
            repeat(channels - 1) { peak = maxOf(peak, abs(pcm.short.toInt())) }
            energy += value.toDouble() * value
            for (tone in coefficients.indices) {
                val next = value + coefficients[tone] * previous[tone] - before[tone]
                before[tone] = previous[tone]; previous[tone] = next
            }
        }
        if (closed.get()) return
        window.frames.addAndGet(count.toLong())
        if (peak > 0) window.nonzero.incrementAndGet()
        if (peak >= 128 && energy > 0 && coefficients.indices.any {
            (previous[it] * previous[it] + before[it] * before[it] - coefficients[it] * previous[it] * before[it]) / (energy * count) > 0.15 })
            window.tones.incrementAndGet()
        window.blocks.incrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publication = null
    }

    private companion object {
        const val SYMBOL_MILLIS = 60L
    }
}
