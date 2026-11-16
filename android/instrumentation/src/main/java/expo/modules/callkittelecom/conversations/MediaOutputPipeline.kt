package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** One request may await its ACK while the tap keeps draining into a bounded next batch. */
internal data class MediaOutputChunk(val firstFrame: Int, val pcm: ByteArray, val stats: JSONObject)

internal data class MediaOutputBatch(val firstFrame: Int, val pcm: ByteArray, val stats: JSONArray)

internal suspend fun <T> pipelineMediaOutput(
    read: suspend () -> MediaOutputChunk,
    send: suspend (MediaOutputBatch) -> T,
    finished: (T) -> Boolean,
    checkPending: (Int, Int) -> Unit,
    pause: suspend () -> Unit = { delay(100) },
): T = coroutineScope {
    val pending = ArrayDeque<MediaOutputChunk>()
    var pendingFrames = 0
    var nextFrame: Int? = null
    var acknowledgement: Deferred<T>? = null
    try {
        while (true) {
            pause()
            val chunk = read()
            check(chunk.pcm.size % 2 == 0 && (nextFrame == null || chunk.firstFrame == nextFrame)) {
                "Output capture frames are not contiguous"
            }
            nextFrame = chunk.firstFrame + chunk.pcm.size / 2
            pending.addLast(chunk)
            pendingFrames += chunk.pcm.size / 2
            checkPending(nextFrame, pending.first().firstFrame)
            val active = acknowledgement
            if (active?.isCompleted == true) {
                val result = active.await()
                acknowledgement = null
                if (finished(result)) return@coroutineScope result
            }
            if (acknowledgement == null) {
                val bytes = ByteArray(pendingFrames * 2)
                val stats = JSONArray()
                var offset = 0
                for (part in pending) {
                    part.pcm.copyInto(bytes, offset)
                    offset += part.pcm.size
                    stats.put(part.stats)
                }
                val batch = MediaOutputBatch(pending.first().firstFrame, bytes, stats)
                pending.clear(); pendingFrames = 0
                acknowledgement = async(start = CoroutineStart.UNDISPATCHED) { send(batch) }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Output pipeline ended without a result")
    } finally { acknowledgement?.cancelAndJoin() }
}
