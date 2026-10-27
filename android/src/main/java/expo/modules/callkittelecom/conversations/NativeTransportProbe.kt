package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport

/** Only readiness scalars escape RTCStats; candidate addresses/certificates are never retained. */
internal class NativeTransportProbe(private val serverId: String, private val scope: CoroutineScope) {
    private data class Observation(val checking: Boolean, val ice: Boolean, val dtls: Boolean,
        val requestedAt: Long, val observedAt: Long)

    // role: 0 raw WebRTC, 1 LiveKit publisher, 2 LiveKit subscriber.
    fun watch(role: Long, getStats: (RTCStatsCollectorCallback) -> Unit, ready: CompletableDeferred<Unit>? = null) = scope.launch {
        val emitted = mutableSetOf<NativeMediaStage>()
        while (isActive) {
            val requestedAt = SystemClock.elapsedRealtimeNanos()
            val observed = withTimeoutOrNull(250) { suspendCancellableCoroutine<Observation?> { result ->
                try { getStats(RTCStatsCollectorCallback { report ->
                    val observedAt = SystemClock.elapsedRealtimeNanos()
                    if (result.isActive) result.resume(read(report, requestedAt, observedAt))
                }) } catch (_: Exception) { if (result.isActive) result.resume(null) }
            } }
            if (observed != null) {
                val values = mapOf("role" to role, "sampled" to 1L, "pollIntervalMs" to 25L,
                    "statsCacheMs" to 50L, "requestNanos" to observed.requestedAt)
                for ((ready, stage) in listOf(observed.checking to NativeMediaStage.ICE_CHECKING,
                    observed.ice to NativeMediaStage.ICE_CONNECTED, observed.dtls to NativeMediaStage.DTLS_CONNECTED)) {
                    if (ready && emitted.add(stage)) NativeMediaSignals.emit(serverId, stage, values, observed.observedAt)
                }
                if (observed.ice && observed.dtls) { ready?.complete(Unit); break }
            }
            delay(25)
        }
    }.also { job ->
        // Cover cancellation before the coroutine body starts as well as cancellation
        // while a stats callback is pending. A readiness waiter must not outlive its watch.
        job.invokeOnCompletion { ready?.cancel() }
    }

    private fun read(report: RTCStatsReport, requestedAt: Long, observedAt: Long): Observation {
        val transports = report.statsMap.values.filter { it.type == "transport" }
        val ice = transports.any { transport ->
            val members = transport.members
            val pair = (members["selectedCandidatePairId"] as? String)?.let { report.statsMap[it] }
            members["iceState"] in setOf("connected", "completed") || pair?.members?.get("state") == "succeeded"
        }
        val dtls = transports.any { it.members["dtlsState"] == "connected" }
        val checking = report.statsMap.values.any { it.type == "candidate-pair" && it.members["state"] == "in-progress" }
        return Observation(checking, ice, dtls, requestedAt, observedAt)
    }
}
