package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import livekit.org.webrtc.AudioTrackSink

/** Decoder readiness and post-release PCM have separate gates and monotonic callback timestamps. */
internal class NativePcmProbe(private val serverId: String) {
    private class Gate(val openedAt: Long, val first: AtomicBoolean = AtomicBoolean(false))
    private val decoded = AtomicBoolean(false)
    @Volatile private var gate: Gate? = null
    @Volatile private var closed = false

    val decoderSink = sink(true)
    val answerSink = sink(false)
    fun arm() { gate = Gate(SystemClock.elapsedRealtimeNanos()) }
    fun disarm() { gate = null }
    fun close() { closed = true; gate = null }

    private fun sink(decoderOnly: Boolean) = object : AudioTrackSink {
        override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, capturedAt: Long) {
            // capturedAt has no established clock relationship with Android elapsedRealtime.
            val enteredAt = SystemClock.elapsedRealtimeNanos()
            val current = if (decoderOnly) null else gate
            if (closed || bits != 16 || (decoded.get() && (current == null || current.first.get()))) return
            val pcm = data.duplicate().order(ByteOrder.nativeOrder())
            var peak = 0
            while (pcm.remaining() >= 2) peak = maxOf(peak, abs(pcm.short.toInt()))
            if (closed || peak < 128) return
            val values = mapOf("frames" to frames.toLong(), "sampleRate" to rate.toLong(),
                "channels" to channels.toLong(), "peak" to peak.toLong(), "threshold" to 128L)
            if (decoded.compareAndSet(false, true))
                NativeMediaSignals.emit(serverId, NativeMediaStage.DECODER_FIRST_PCM, values, enteredAt)
            if (current != null && gate === current && enteredAt >= current.openedAt && current.first.compareAndSet(false, true))
                NativeMediaSignals.emit(serverId, NativeMediaStage.FIRST_AUDIO, values, enteredAt)
        }
    }
}
