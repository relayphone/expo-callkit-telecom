package expo.modules.callkittelecom.conversations

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONObject

/** Read-only, frame-paired observation of the first full-band channel at capture post-processing. */
internal class MediaApmCapture(private val markerId: String, private val now: () -> Long) : AudioProcessorInterface {
    private enum class Problem { FORMAT, MISSING_SOURCE, SOURCE_NOT_CONSUMED, THREAD_MISMATCH, FRAME_GAP, NONFINITE }
    private data class Source(val first: Long, val pcm: ByteArray, val thread: Long, val atNs: Long)
    private val lock = Any()
    private val source = ByteArray(144000 * 2)
    private val post = ByteArray(144000 * 4)
    private val blocks = mutableListOf<List<Long>>()
    private var running = false
    private var rate = 0
    private var channels = 0
    private var problem: Problem? = null
    private var pending: Source? = null
    private var first: Long? = null
    private var frames = 0
    private var trailingSource = false

    override fun isEnabled() = true
    override fun getName() = "Read-only PCM observation"
    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) = synchronized(lock) {
        rate = sampleRateHz; channels = numChannels
    }
    override fun resetAudioProcessing(newRate: Int) = synchronized(lock) { rate = newRate }

    fun start() = synchronized(lock) { check(!running && frames == 0); running = true }

    fun returned(firstFrame: Long, count: Int, sampleRate: Int, channelCount: Int, buffer: ByteBuffer) = synchronized(lock) {
        if (!running || frames == 144000 || problem != null) return@synchronized
        if (pending != null) { problem = Problem.SOURCE_NOT_CONSUMED; return@synchronized }
        if (sampleRate != 48000 || channelCount != 1 || count != 480 || buffer.capacity() != count * 2 ||
            ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) { problem = Problem.FORMAT; return@synchronized }
        val pcm = ByteArray(count * 2)
        buffer.asReadOnlyBuffer().apply { clear() }.get(pcm)
        pending = Source(firstFrame, pcm, Thread.currentThread().id, now())
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) = synchronized(lock) {
        if (!running || frames == 144000 || problem != null) return@synchronized
        val input = pending
        // Arming can happen between the preceding source callback and its native processing.
        if (input == null) { if (first != null) problem = Problem.MISSING_SOURCE; return@synchronized }
        pending = null
        if (input.thread != Thread.currentThread().id) { problem = Problem.THREAD_MISMATCH; return@synchronized }
        if (rate != 48000 || channels != 1 || numFrames != 480 || numBands != 3 || buffer.capacity() != numFrames * 4) {
            problem = Problem.FORMAT; return@synchronized
        }
        if (first != null && input.first != first!! + frames) { problem = Problem.FRAME_GAP; return@synchronized }
        val pcm = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply { clear() }
        for (index in 0 until numFrames) if (!pcm.getFloat(index * 4).isFinite()) {
            problem = Problem.NONFINITE; return@synchronized
        }
        first = first ?: input.first
        input.pcm.copyInto(source, frames * 2)
        pcm.get(post, frames * 4, numFrames * 4)
        blocks += listOf(input.first, frames.toLong(), input.atNs, now(), input.thread)
        frames += numFrames
    }

    fun stop() = synchronized(lock) {
        if (running) { trailingSource = pending != null; running = false; pending = null }
    }

    fun finish(): JSONObject = synchronized(lock) {
        stop()
        JSONObject().put("markerId", markerId).put("sampleRate", 48000).put("channels", 1)
            .put("clock", "android.elapsedRealtimeNanos").put("firstSourceFrame", first ?: JSONObject.NULL)
            .put("frames", frames).put("capacityFrames", 144000).put("error", problem?.name ?: JSONObject.NULL)
            .put("discardedTrailingSource", trailingSource)
            .put("sourceEncoding", "pcm16le").put("postEncoding", "float32le-s16-scale")
            .put("sourcePcm", Base64.getEncoder().encodeToString(source.copyOf(frames * 2)))
            .put("postPcm", Base64.getEncoder().encodeToString(post.copyOf(frames * 4)))
            .put("columns", org.json.JSONArray(listOf("sourceFrame", "observationFrame", "sourceAtNs", "postAtNs", "thread")))
            .put("blocks", org.json.JSONArray(blocks))
    }
}
