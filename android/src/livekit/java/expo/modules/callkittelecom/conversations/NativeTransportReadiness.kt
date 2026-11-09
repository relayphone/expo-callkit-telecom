package expo.modules.callkittelecom.conversations

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport

/** Polls native transport readiness and cancels pending waiters when the watch ends. */
internal class NativeTransportReadiness(private val scope: CoroutineScope) {
    internal data class State(val iceConnected: Boolean, val dtlsConnected: Boolean) {
        val isConnected: Boolean get() = iceConnected && dtlsConnected
    }

    fun watch(
        getStats: (RTCStatsCollectorCallback) -> Unit,
        ready: CompletableDeferred<Unit>,
        onRequest: (() -> RTCStatsCollectorCallback)? = null,
    ): Job = scope.launch {
        while (isActive) {
            val observer = runCatching { onRequest?.invoke() }.getOrNull()
            val observed = withTimeoutOrNull(250) { suspendCancellableCoroutine<State?> { result ->
                val delivered = AtomicBoolean(false)
                try { getStats(RTCStatsCollectorCallback { report ->
                    if (result.isActive && delivered.compareAndSet(false, true)) {
                        // Only the first live callback reaches the observer; observer failures do not replace readiness.
                        runCatching { observer?.onStatsDelivered(report) }
                        if (result.isActive) result.resume(read(report))
                    }
                }) } catch (_: Exception) {
                    if (result.isActive && delivered.compareAndSet(false, true)) result.resume(null)
                }
            } }
            if (observed?.isConnected == true) { ready.complete(Unit); break }
            delay(25)
        }
    }.also { job ->
        // This also covers cancellation before the coroutine body starts.
        job.invokeOnCompletion { ready.cancel() }
    }

    companion object {
        fun read(report: RTCStatsReport): State {
            val transports = report.statsMap.values.filter { it.type == "transport" }
            val ice = transports.any { transport ->
                val members = transport.members
                val pair = (members["selectedCandidatePairId"] as? String)?.let { report.statsMap[it] }
                members["iceState"] in setOf("connected", "completed") || pair?.members?.get("state") == "succeeded"
            }
            val dtls = transports.any { it.members["dtlsState"] == "connected" }
            return State(ice, dtls)
        }
    }
}
