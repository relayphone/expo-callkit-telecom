package expo.modules.callkittelecom.conversations

import java.nio.ByteBuffer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

internal data class VideoPlaneReceipt(val width: Int, val height: Int, val stride: Int, val complete: Boolean,
    val minimum: Int?, val maximum: Int?, val sample: List<Int>)
internal enum class VideoImageKind { SOURCE, CONTENT_FREE, AMBIGUOUS }
internal data class VideoImageReceipt(val kind: VideoImageKind, val marker: SyntheticVideoMarker?,
    val y: VideoPlaneReceipt, val u: VideoPlaneReceipt, val v: VideoPlaneReceipt)
internal enum class VideoBufferKind { I420, TEXTURE, UNSUPPORTED }
internal data class VideoDecodedReceipt(val atNs: Long, val mediaTimestampNs: Long, val rotation: Int, val bufferKind: VideoBufferKind,
    val image: VideoImageReceipt)

/** Full visible planes are scanned, including chroma; row padding is never image content. */
internal object VideoImageClassifier {
    private fun plane(data: ByteBuffer?, width: Int, height: Int, stride: Int): Pair<VideoPlaneReceipt, ByteArray> {
        val valid = width in 1..1920 && height in 1..1080 && stride in width..16384 && data != null &&
            data.remaining().toLong() >= (height - 1L) * stride + width
        if (!valid) return VideoPlaneReceipt(width, height, stride, false, null, null, emptyList()) to byteArrayOf()
        val input = data!!.duplicate(); val start = input.position()
        val pixels = ByteArray(width * height)
        var minimum = 255; var maximum = 0
        repeat(height) { row -> repeat(width) { x ->
            val value = input.get(start + row * stride + x)
            pixels[row * width + x] = value
            minimum = minOf(minimum, value.toInt() and 255); maximum = maxOf(maximum, value.toInt() and 255)
        } }
        return VideoPlaneReceipt(width, height, stride, true, minimum, maximum,
            pixels.take(16).map { it.toInt() and 255 }) to pixels
    }
    fun inspect(width: Int, height: Int, y: ByteBuffer?, sy: Int, u: ByteBuffer?, su: Int,
        v: ByteBuffer?, sv: Int, rotation: Int = 0): VideoImageReceipt {
        val (yp, pixels) = plane(y, width, height, sy)
        val (up, _) = plane(u, (width + 1) / 2, (height + 1) / 2, su)
        val (vp, _) = plane(v, (width + 1) / 2, (height + 1) / 2, sv)
        val complete = yp.complete && up.complete && vp.complete && rotation == 0
        val marker = if (complete) runCatching { SyntheticVideoMarker.decode(pixels, width, height) }.getOrNull() else null
        val sourceGeometry = width == 160 && height == 120 || width == 320 && height == 240
        val uniform = complete && listOf(yp, up, vp).all { it.maximum!! - it.minimum!! <= 4 }
        return VideoImageReceipt(when {
            marker != null -> VideoImageKind.SOURCE
            uniform && !sourceGeometry -> VideoImageKind.CONTENT_FREE
            else -> VideoImageKind.AMBIGUOUS
        }, marker, yp, up, vp)
    }
}

/** Remote decoded callbacks only. Conversion references are released before returning. */
internal class EmulatorVideoSink(private val nowNs: () -> Long) : VideoSink {
    private val arrivals = mutableListOf<VideoDecodedReceipt>()
    private var overflow = false
    override fun onFrame(frame: VideoFrame) {
        val at = nowNs()
        val kind = when (frame.buffer) {
            is VideoFrame.I420Buffer -> VideoBufferKind.I420
            is VideoFrame.TextureBuffer -> VideoBufferKind.TEXTURE
            else -> VideoBufferKind.UNSUPPORTED
        }
        val buffer = runCatching { frame.buffer.toI420() }.getOrNull()
        val image = try {
            VideoImageClassifier.inspect(frame.buffer.width, frame.buffer.height, buffer?.dataY, buffer?.strideY ?: 0,
                buffer?.dataU, buffer?.strideU ?: 0, buffer?.dataV, buffer?.strideV ?: 0, frame.rotation)
        } finally { buffer?.release() }
        synchronized(this) {
            if (arrivals.size < 4096) arrivals += VideoDecodedReceipt(at, frame.timestampNs, frame.rotation, kind, image)
            else overflow = true
        }
    }
    @Synchronized fun snapshot(): List<VideoDecodedReceipt> = arrivals.toList()
    @Synchronized fun overflowed(): Boolean = overflow
}
