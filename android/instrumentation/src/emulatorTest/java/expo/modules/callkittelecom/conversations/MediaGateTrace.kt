package expo.modules.callkittelecom.conversations

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded copies only; never writes a native buffer or changes gate/phase state. */
internal class MediaGateTrace(private val now: () -> Long) {
    enum class Phase { HELD, MUTED }
    enum class Direction { PLAYBACK, CAPTURE }
    enum class Point { ARMED, REQUESTED, PLATFORM_ACKNOWLEDGED, COMPLETED, CORE_MUTE_OBSERVED,
        PHASE_ACKNOWLEDGED, PLAYBACK_CLOSED_AT_ENTRY, CAPTURE_CLOSED_AT_ENTRY }
    private enum class Problem { FORMAT, GATE_UNAVAILABLE }
    data class Entry(val phase: Phase, val direction: Direction, val phaseId: Long,
        val enteredNs: Long, val gateReadNs: Long, val gateClosed: Boolean?,
        val firstFrame: Long, val frames: Int, val rate: Int, val channels: Int, val thread: Long)
    private data class Block(val entry: Entry, val exitedNs: Long, val peak: Int, val rms: Double,
        val pcmOffset: Int, val pcmBytes: Int)
    private data class Event(val phase: Phase, val point: Point, val atNs: Long, val closed: Boolean?, val phaseId: Long?, val origin: NativeActionOrigin?)
    private val lock = Any()
    @Volatile private var phase: Phase? = null
    private val blocks = mutableListOf<Block>()
    private val inFlight = mutableListOf<Entry>()
    private val events = mutableListOf<Event>()
    private val closedSeen = mutableSetOf<Pair<Phase, Direction>>()
    private val pcm = ByteArray(768 * 960)
    private var pcmBytes = 0
    private var overflow = false
    private var problem: Problem? = null
    val isArmed: Boolean get() = phase != null

    fun arm(value: Phase) { phase = value; event(Point.ARMED, now()) }
    fun stop() { phase = null }
    suspend fun recording(value: Phase, work: suspend () -> Unit) {
        arm(value)
        try { work() } finally { stop() }
    }
    fun event(point: Point, atNs: Long = now(), closed: Boolean? = null, phaseId: Long? = null,
        origin: NativeActionOrigin? = null) = synchronized(lock) {
        val current = phase ?: return@synchronized
        if (events.size == 64) overflow = true else events += Event(current, point, atNs, closed, phaseId, origin)
    }
    fun control(value: NativeControlObservation) {
        if ((value.kind == NativeControlKind.HOLD && phase == Phase.HELD) ||
            (value.kind == NativeControlKind.MUTE && phase == Phase.MUTED))
            event(when (value.point) {
                NativeControlPoint.REQUESTED -> Point.REQUESTED
                NativeControlPoint.PLATFORM_ACKNOWLEDGED -> Point.PLATFORM_ACKNOWLEDGED
                NativeControlPoint.COMPLETED -> Point.COMPLETED
            }, value.atNs, value.closed, origin = value.origin)
    }
    fun phaseAcknowledged(phaseId: Long) = event(Point.PHASE_ACKNOWLEDGED, phaseId = phaseId)

    fun enter(direction: Direction, phaseId: Long, enteredNs: Long, closed: Boolean?,
        firstFrame: Long, frames: Int, rate: Int, channels: Int): Entry? {
        val current = phase ?: return null
        val readNs = now()
        if (channels !in 1..2 || rate != 48000 || frames != 480) {
            synchronized(lock) { problem = Problem.FORMAT }; return null
        }
        val entry = Entry(current, direction, phaseId, enteredNs, readNs, closed,
            firstFrame, frames, rate, channels, Thread.currentThread().id)
        synchronized(lock) {
            if (blocks.size + inFlight.size >= 512) { overflow = true; return null }
            inFlight += entry
            if (closed == null) problem = Problem.GATE_UNAVAILABLE
            if (closed == true && closedSeen.add(current to direction)) {
                if (events.size == 64) overflow = true
                else events += Event(current, if (direction == Direction.PLAYBACK) Point.PLAYBACK_CLOSED_AT_ENTRY
                    else Point.CAPTURE_CLOSED_AT_ENTRY, readNs, true, phaseId, null)
            }
        }
        return entry
    }
    fun exit(entry: Entry?, data: ByteArray) {
        if (entry == null) return
        if (data.size != entry.frames * entry.channels * 2) {
            synchronized(lock) { problem = Problem.FORMAT; inFlight.remove(entry) }; return
        }
        val samples = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0; var energy = 0.0
        while (samples.remaining() >= 2) {
            val sample = samples.short.toInt()
            peak = maxOf(peak, abs(sample)); energy += sample.toDouble() * sample
        }
        synchronized(lock) {
            inFlight.remove(entry)
            if (blocks.size == 512 || pcmBytes + data.size > pcm.size) { overflow = true; return@synchronized }
            data.copyInto(pcm, pcmBytes)
            blocks += Block(entry, now(), peak, sqrt(energy / (data.size / 2)), pcmBytes, data.size)
            pcmBytes += data.size
        }
    }
    fun snapshot() = synchronized(lock) {
        JSONObject().put("clock", "android.elapsedRealtimeNanos").put("overflow", overflow)
            .put("error", problem?.name ?: JSONObject.NULL)
            .put("gateSources", JSONObject().put("playback", "JavaAudioDeviceModule.audioOutput.speakerMute")
                .put("capture", "LocalTrackPublication.muted / track.enabled / absent publication"))
            .put("playbackObservation", "Callback follows AudioTrack.write; entry flag does not timestamp the earlier buffer-fill/write gate decision")
            .put("platformAcknowledgement", JSONObject().put("hold", "successful Core setInactive/setActive return")
                .put("mute", "AudioManager microphone-mute setter return; Core flow observation is separate"))
            .put("inFlight", JSONArray(inFlight.map { entry -> JSONObject().put("phase", entry.phase.name)
                .put("direction", entry.direction.name).put("phaseId", entry.phaseId).put("enteredNs", entry.enteredNs)
                .put("gateReadNs", entry.gateReadNs).put("gateClosedAtEntry", entry.gateClosed ?: JSONObject.NULL)
                .put("firstFrame", entry.firstFrame) }))
            .put("pcmEncoding", "pcm16le").put("pcm", Base64.getEncoder().encodeToString(pcm.copyOf(pcmBytes)))
            .put("events", JSONArray(events.map { event -> JSONObject().put("phase", event.phase.name)
                .put("point", event.point.name).put("atNs", event.atNs).put("closed", event.closed ?: JSONObject.NULL)
                .put("phaseId", event.phaseId ?: JSONObject.NULL).put("origin", event.origin?.name ?: JSONObject.NULL) }))
            .put("columns", JSONArray(listOf("phase", "direction", "phaseId", "enteredNs", "gateReadNs", "exitedNs",
                "gateClosedAtEntry", "firstFrame", "frames", "rate", "channels", "peak", "rms", "pcmOffset", "pcmBytes", "thread")))
            .put("blocks", JSONArray(blocks.map { block -> block.entry.let { entry -> JSONArray(listOf(
                entry.phase.name, entry.direction.name, entry.phaseId, entry.enteredNs, entry.gateReadNs, block.exitedNs,
                entry.gateClosed ?: JSONObject.NULL, entry.firstFrame, entry.frames, entry.rate, entry.channels,
                block.peak, block.rms, block.pcmOffset, block.pcmBytes, entry.thread)) } }))
    }
}
