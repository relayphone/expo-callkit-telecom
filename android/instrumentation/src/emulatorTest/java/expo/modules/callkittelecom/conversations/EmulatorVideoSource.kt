package expo.modules.callkittelecom.conversations

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class VideoProducerReceipt(val clockID: String, val generated: List<SyntheticVideoMarker>,
    val stoppedAtUs: Long?, val finalCounter: Long?, val finalEpoch: Long?, val pacingTaskJoined: Boolean,
    val postStopCaptureRejects: Long, val skippedTicks: Long, val rejectedSubmissions: Long, val failed: Boolean)

/** Generation and synchronous submission share the stop boundary; LiveKit stopCapture is not a barrier. */
internal class EmulatorVideoSource(private val fixture: UUID, private val direction: Int, private val epoch: Long,
    private val nowUs: () -> Long, private val pause: suspend (Long) -> Unit = { delay(it) },
    private val submit: (SyntheticVideoMarker) -> Unit) {
    private val serial = Mutex()
    private val clockID = UUID.randomUUID().toString()
    private val generated = mutableListOf<SyntheticVideoMarker>()
    private var counter = 0L; private var skipped = 0L; private var rejected = 0L; private var postStop = 0L
    private var stopped: Long? = null; private var finalCounter: Long? = null; private var finalEpoch: Long? = null
    private var pumping: Job? = null; private var joined = true; private var failed = false
    init { SyntheticVideoMarker(fixture, direction, epoch, 0, 0) }

    suspend fun capture(): SyntheticVideoMarker = serial.withLock { captureLocked() }
    private fun captureLocked(): SyntheticVideoMarker {
        if (stopped != null) { postStop++; error("Video source stopped") }
        check(generated.size < 4096 && counter <= 0xffffffffL) { "Video source overflow" }
        val marker = SyntheticVideoMarker(fixture, direction, epoch, counter++, nowUs())
        check(marker.sourceTimeUs >= (generated.lastOrNull()?.sourceTimeUs ?: 0))
        generated += marker
        try { submit(marker) } catch (error: Throwable) { rejected++; failed = true; throw error }
        return marker
    }
    suspend fun start(scope: CoroutineScope) = serial.withLock {
        check(pumping == null && stopped == null)
        joined = false
        pumping = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val end = nowUs() + 180_000_000L
                var next = nowUs()
                while (true) {
                    ensureActive()
                    val continuePacing = serial.withLock {
                        if (stopped != null) false else {
                            val at = nowUs(); check(at < end) { "Video source duration exhausted" }
                            while (next + 100_000 <= at) { counter++; skipped++; next += 100_000 }
                            captureLocked()
                            next += 100_000
                            while (next <= nowUs()) { counter++; skipped++; next += 100_000 }
                            next = maxOf(next, nowUs() + 100_000)
                            true
                        }
                    }
                    if (!continuePacing) break
                    pause(maxOf(1, (next - nowUs() + 999) / 1000))
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) { serial.withLock { if (stopped == null) failed = true } }
                if (error !is CancellationException) throw error
            } finally { withContext(NonCancellable) { serial.withLock { joined = true } } }
        }.also { it.start() }
    }
    suspend fun stop(): VideoProducerReceipt = withContext(NonCancellable) {
        val task = serial.withLock {
            if (stopped == null) {
                stopped = nowUs(); finalCounter = generated.lastOrNull()?.counter; finalEpoch = epoch
            }
            pumping
        }
        task?.cancelAndJoin()
        serial.withLock { joined = true }
        snapshot()
    }
    suspend fun snapshot(): VideoProducerReceipt = serial.withLock {
        VideoProducerReceipt(clockID, generated.toList(), stopped, finalCounter, finalEpoch, joined,
            postStop, skipped, rejected, failed)
    }
}
