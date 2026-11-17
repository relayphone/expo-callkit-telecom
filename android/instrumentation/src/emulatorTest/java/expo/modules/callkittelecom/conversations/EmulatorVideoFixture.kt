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
    private val frames: VideoFramePool
    init {
        MediaTestRuntime.activate(context)
        frames = VideoFramePool()
        capturer = VideoFrameCapturer()
        track = room.localParticipant.createVideoTrack("synthetic-camera", capturer,
            LocalVideoTrackOptions(captureParams = VideoCaptureParameter(160, 120, 10)))
        track.startCapture()
        source = EmulatorVideoSource(fixture, direction, epoch, { SystemClock.elapsedRealtimeNanos() / 1000 }) { marker ->
            frames.push(marker, capturer::pushVideoFrame)
        }
    }
    suspend fun stop(): VideoProducerReceipt {
        val receipt = source.stop()
        track.stopCapture()
        return receipt
    }
}

/** A retained encoder reference keeps its pixels out of the reusable pool. */
internal class VideoFramePool {
    private val buffers = ArrayDeque<ByteBuffer>().apply {
        repeat(4) { add(ByteBuffer.allocateDirect(160 * 120 * 3 / 2)) }
    }
    fun push(marker: SyntheticVideoMarker, submit: (VideoFrame) -> Unit) {
        val pixels = marker.luma()
        val bytes = synchronized(buffers) { checkNotNull(buffers.removeFirstOrNull()) { "Video buffer pool exhausted" } }
        bytes.clear(); bytes.put(pixels); while (bytes.hasRemaining()) bytes.put(128.toByte())
        fun plane(start: Int, size: Int) = bytes.duplicate().apply { position(start); limit(start + size) }.slice()
        val buffer = JavaI420Buffer.wrap(160, 120, plane(0, 19200), 160, plane(19200, 4800), 80,
            plane(24000, 4800), 80) { synchronized(buffers) { buffers.addLast(bytes) } }
        val frame = VideoFrame(buffer, 0, marker.sourceTimeUs * 1000)
        try { submit(frame) } finally { frame.release() }
    }
}
