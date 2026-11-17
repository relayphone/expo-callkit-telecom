package expo.modules.callkittelecom.conversations

import android.content.Context
import android.os.SystemClock
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.video.VideoFrameCapturer
import java.nio.ByteBuffer
import java.util.UUID
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame

/** Only the instrumentation application owns this track; the call SDK has no camera dependency. */
internal class EmulatorVideoFixture(context: Context, room: Room, fixture: UUID, direction: Int, epoch: Long) {
    private val capturer: VideoFrameCapturer
    val track: io.livekit.android.room.track.LocalVideoTrack
    val source: EmulatorVideoSource
    private val buffers = ArrayDeque<ByteBuffer>()
    init {
        MediaTestRuntime.activate(context)
        repeat(4) { buffers.add(ByteBuffer.allocateDirect(160 * 120 * 3 / 2)) }
        capturer = VideoFrameCapturer()
        track = room.localParticipant.createVideoTrack("synthetic-camera", capturer,
            LocalVideoTrackOptions(captureParams = VideoCaptureParameter(160, 120, 10)))
        track.startCapture()
        source = EmulatorVideoSource(fixture, direction, epoch, { SystemClock.elapsedRealtimeNanos() / 1000 }) { marker ->
            val bytes = synchronized(buffers) { checkNotNull(buffers.removeFirstOrNull()) { "Video buffer pool exhausted" } }
            bytes.clear(); bytes.put(marker.luma()); while (bytes.hasRemaining()) bytes.put(128.toByte())
            fun plane(start: Int, size: Int) = bytes.duplicate().apply { position(start); limit(start + size) }.slice()
            val buffer = JavaI420Buffer.wrap(160, 120, plane(0, 19200), 160, plane(19200, 4800), 80,
                plane(24000, 4800), 80) { synchronized(buffers) { buffers.addLast(bytes) } }
            val frame = VideoFrame(buffer, 0, marker.sourceTimeUs * 1000)
            try { capturer.pushVideoFrame(frame) } finally { frame.release() }
        }
    }
    suspend fun stop(): VideoProducerReceipt {
        val receipt = source.stop()
        track.stopCapture()
        return receipt
    }
}
