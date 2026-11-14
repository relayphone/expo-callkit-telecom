package expo.modules.callkittelecom.conversations

import android.content.Context
import android.media.AudioFormat
import android.os.SystemClock
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteAudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** Software PCM qualification only. This implementation exists solely in the emulatorTest source set. */
internal class EmulatorMediaHooks(context: Context, markerId: String, tapEnabled: Boolean = true,
    private val requestedRate: Int = 48000, private val emission: Boolean = true) : NativeAudioHooks {
    val trace = MediaCaptureTrace()
    val postApm = MediaApmCapture(markerId, SystemClock::elapsedRealtimeNanos)
    override val capturePostProcessor get() = postApm
    // Match SDK testing/marker.ts: UTF-8 FNV-1a, four sync slots, low sixteen hash bits.
    private val frequencies = run {
        var hash = 2166136261L
        for (byte in markerId.toByteArray(Charsets.UTF_8)) hash = ((hash xor (byte.toLong() and 255)) * 16777619L) and 0xffffffffL
        val tones = doubleArrayOf(650.0, 950.0, 1350.0, 1850.0)
        tones + DoubleArray(8) { tones[((hash ushr (it * 2)) and 3).toInt()] }
    }
    private val signals: Map<Int, ShortArray>
    private val wrongSignals: Map<Int, ShortArray>
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
    @Volatile private var publication: LocalTrackPublication? = null
    @Volatile private var remoteTrack: RemoteAudioTrack? = null
    private val outputLock = Any()
    private val outputPcm = ShortArray(48000 * 6)
    private var outputFrames = 0
    private var drainedFrames = 0
    private var captureOutput = false
    val directArrayVerified: Boolean

    init {
        MediaTestRuntime.activate(context)
        val buffer = ByteBuffer.allocateDirect(16)
        directArrayVerified = buffer.hasArray() && runCatching {
            buffer.put(0, 0x5a.toByte())
            buffer.array()[buffer.arrayOffset()] == buffer.get(0)
        }.getOrDefault(false)
        if (!tapEnabled || !directArrayVerified) throw MediaHookUnavailable(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE)
        // Real-time capture only copies already-qualified PCM; generation must not stall AudioRecord.
        signals = listOf(8000, 48000).associateWith { rate ->
            ShortArray(rate / 50 * if (emission) 51 else 36) { sample(rate, it) }
        }
        wrongSignals = listOf(8000, 48000).associateWith { rate -> ShortArray(rate) {
            ((0.2 * sin(2 * PI * 440 * it / rate)).toFloat().toDouble() * 32767).roundToInt().toShort()
        } }

    }

    fun beginPhase() { decoded.set(Window()); output.set(Window()) }
    fun decoded(): Observation { available(); return decoded.get().snapshot() }
    fun output(): Observation { available(); return output.get().snapshot() }
    fun observeOutput(enabled: Boolean) { observingOutput.set(enabled) }
    fun startOutputCapture() = synchronized(outputLock) {
        available(); outputFrames = 0; drainedFrames = 0; captureOutput = true
    }
    fun stopOutputCapture() = synchronized(outputLock) { captureOutput = false }
    private fun capturedOutputFrames() = synchronized(outputLock) { outputFrames }
    fun drainOutput(): Pair<Int, ByteArray> = synchronized(outputLock) {
        available()
        val count = outputFrames - drainedFrames
        check(count <= 24000) { "Output capture consumer fell behind" }
        val first = drainedFrames
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (drainedFrames < outputFrames) bytes.putShort(outputPcm[drainedFrames++])
        first to bytes.array()
    }
    suspend fun outputStats(baseline: Boolean = false): JSONObject {
        val first = if (baseline) 0 else capturedOutputFrames()
        val reports = checkNotNull(remoteTrack) { "Missing remote track" }.getRTCStats()?.statsMap?.values
        val report = reports?.singleOrNull { it.type == "inbound-rtp" &&
            (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
        val names = mapOf("packets_lost" to "packetsLost", "concealed_samples" to "concealedSamples",
            "inserted_samples_for_deceleration" to "insertedSamplesForDeceleration",
            "removed_samples_for_acceleration" to "removedSamplesForAcceleration")
        return JSONObject().put("fromFrame", first).put("frame", if (baseline) 0 else capturedOutputFrames())
            .put("counters", JSONObject(names.mapValues { (_, field) -> report?.members?.get(field) ?: JSONObject.NULL }))
    }
    private fun available() { failure.get()?.let { throw MediaHookUnavailable(it) }; check(!closed.get()) }

    override val decodedSink = object : AudioTrackSink {
        override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, capturedAt: Long) {
            if (bits != 16) { failure.set(MediaHookFailure.UNSUPPORTED_PCM); return }
            observe(decoded.get(), data.duplicate().order(ByteOrder.nativeOrder()), rate, channels)
        }
    }

    override fun capture(pacing: AudioBufferCallback) = object : AudioBufferCallback {
        override fun onBuffer(buffer: ByteBuffer, format: Int, channels: Int, rate: Int, bytesRead: Int, timestamp: Long): Long {
            val entered = SystemClock.elapsedRealtimeNanos()
            // Always retain the production recordingless pump's pacing and lifecycle observations.
            val result = pacing.onBuffer(buffer, format, channels, rate, bytesRead, timestamp)
            if (!closed.get()) {
                if (format != AudioFormat.ENCODING_PCM_16BIT || channels !in 1..2 || rate !in signals ||
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
                        val signal = if (stimulus == Stimulus.WRONG) wrongSignals.getValue(rate) else signals.getValue(rate)
                        val sample = if (stimulus == Stimulus.ZERO) 0.toShort() else signal[(index % signal.size).toInt()]
                        repeat(channels) { pcm.putShort(sample) }
                    }
                    injectedFrames.addAndGet(frames.toLong())
                    if (stimulus == Stimulus.MARKER) {
                        val wordFrames = if (emission) rate.toLong() / 50 * 51 else rate.toLong() * SYMBOL_MILLIS * frequencies.size / 1000
                        generatedWords.addAndGet((first + frames) / wordFrames - first / wordFrames)
                    }
                    if (bytesRead == 0) recordinglessCallbacks.incrementAndGet()
                    val crc = CRC32().apply { update(buffer.duplicate().apply { clear() }) }.value
                    trace.returned(entered, first, frames, rate, bytesRead, publication?.muted, crc)
                    postApm.returned(first, frames, rate, channels, buffer)
                }
            }
            return result
        }
    }

    private fun sample(rate: Int, index: Int): Short {
        val slotFrames = rate / 50 * 3
        val pre = (rate * 3 / 20.0).roundToInt()
        val post = pre + slotFrames * frequencies.size
        val cycle = rate / 50 * 51
        val position = index % if (emission) cycle else slotFrames * frequencies.size
        val inMarker = !emission || (position >= pre && position < post)
        val markerPosition = if (emission) position - pre else position
        val local = if (inMarker) markerPosition % slotFrames else if (position < pre) position else position - post
        val frequency = if (inMarker) frequencies[markerPosition / slotFrames] else 400.0
        val edge = if (inMarker) minOf(local, slotFrames - local) else if (position < pre) local else cycle - position
        val envelope = 0.5 * (1 - cos(PI * minOf(1.0, edge / (rate / 200.0))))
        val normalized = (0.2 * envelope * sin(2 * PI * frequency * local / rate)).toFloat()
        return (normalized.toDouble() * 32767).roundToInt().toShort()
    }

    override fun configureOutput(builder: JavaAudioDeviceModule.Builder,
        observer: JavaAudioDeviceModule.PlaybackSamplesReadyCallback) {
        available()
        require(requestedRate == 48000)
        builder.setSampleRate(requestedRate)
        builder.setPlaybackSamplesReadyCallback { samples ->
            observer.onWebRtcAudioTrackSamplesReady(samples)
            if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) failure.set(MediaHookFailure.UNSUPPORTED_PCM)
            else if (observingOutput.get()) observe(output.get(),
                ByteBuffer.wrap(samples.data).order(ByteOrder.nativeOrder()), samples.sampleRate, samples.channelCount)
            if (observingOutput.get()) synchronized(outputLock) {
                if (captureOutput) {
                    if (samples.sampleRate != 48000 || samples.channelCount !in 1..2 ||
                        samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) failure.set(MediaHookFailure.UNSUPPORTED_PCM)
                    else {
                        val pcm = ByteBuffer.wrap(samples.data).order(ByteOrder.nativeOrder())
                        while (pcm.remaining() >= samples.channelCount * 2) {
                            val value = pcm.short
                            repeat(samples.channelCount - 1) { pcm.short }
                            if (outputFrames == outputPcm.size) { failure.set(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE); break }
                            outputPcm[outputFrames++] = value
                        }
                    }
                }
            }
        }
    }

    override fun published(publication: LocalTrackPublication) { this.publication = publication }
    override fun subscribed(track: RemoteAudioTrack) { remoteTrack = track }
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
        remoteTrack = null
        postApm.stop()
        stopOutputCapture()
    }

    private companion object {
        const val SYMBOL_MILLIS = 60L
    }
}
