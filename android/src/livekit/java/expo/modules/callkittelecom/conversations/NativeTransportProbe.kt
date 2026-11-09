package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import livekit.org.webrtc.RTCStatsCollectorCallback

/** Records accepted transport samples without owning readiness or polling. */
internal class NativeTransportProbe(private val serverId: String, private val role: Long) {
    private val emitted = ConcurrentHashMap.newKeySet<NativeMediaStage>()

    fun request(): RTCStatsCollectorCallback {
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        return RTCStatsCollectorCallback { report ->
            val observedAt = SystemClock.elapsedRealtimeNanos()
            val state = NativeTransportReadiness.read(report)
            val checking = report.statsMap.values.any { it.type == "candidate-pair" && it.members["state"] == "in-progress" }
            val values = mapOf("role" to role, "sampled" to 1L, "pollIntervalMs" to 25L,
                "statsCacheMs" to 50L, "requestNanos" to requestedAt)
            for ((present, stage) in listOf(checking to NativeMediaStage.ICE_CHECKING,
                state.iceConnected to NativeMediaStage.ICE_CONNECTED, state.dtlsConnected to NativeMediaStage.DTLS_CONNECTED)) {
                if (present && emitted.add(stage)) NativeMediaSignals.emit(serverId, stage, values, observedAt)
            }
        }
    }
}
