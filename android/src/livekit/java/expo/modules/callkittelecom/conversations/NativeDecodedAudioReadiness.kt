package expo.modules.callkittelecom.conversations

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import livekit.org.webrtc.AudioTrackSink

/** Waits for non-silent decoded 16-bit PCM without authorizing capture or audible output. */
internal class NativeDecodedAudioReadiness : AudioTrackSink {
    private val ready = CompletableDeferred<Unit>()

    suspend fun awaitDecoded() { ready.await() }
    fun close() { ready.cancel() }

    override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, capturedAt: Long) {
        if (bits != 16 || ready.isCompleted) return
        val pcm = data.duplicate().order(ByteOrder.nativeOrder())
        while (pcm.remaining() >= 2) {
            if (abs(pcm.short.toInt()) >= 128) {
                ready.complete(Unit)
                return
            }
        }
    }
}
