package expo.modules.callkittelecom.conversations

import org.json.JSONObject

/** One wall/elapsed pair; only intake and the ended snapshot are retained. */
internal data class ExpiryClockSample(val wallMillis: Long, val elapsedMillis: Long) {
    fun json() = JSONObject().put("wallMillis", wallMillis).put("elapsedMillis", elapsedMillis)
}

internal fun expiryClockDiagnostics(endedAt: Long?, expiresAtMillis: Long,
    intake: ExpiryClockSample, end: ExpiryClockSample?): String = JSONObject().apply {
    put("endedAt", endedAt ?: JSONObject.NULL)
    put("endedAtClock", "System.currentTimeMillis")
    put("expiresAtMillis", expiresAtMillis)
    put("expiresAtClock", "System.currentTimeMillis")
    put("expiryOrigin", "test push: System.currentTimeMillis() + 5000; incomingCall JSON expiresAtMillis decoded unchanged")
    put("endedMinusExpiryMs", endedAt?.let { it - expiresAtMillis } ?: JSONObject.NULL)
    put("wallClock", "System.currentTimeMillis")
    put("elapsedClock", "SystemClock.elapsedRealtime")
    put("intake", intake.json())
    put("end", end?.json() ?: JSONObject.NULL)
    if (end != null) {
        val wall = end.wallMillis - intake.wallMillis
        val elapsed = end.elapsedMillis - intake.elapsedMillis
        put("wallDeltaMs", wall); put("elapsedDeltaMs", elapsed)
        put("wallMinusElapsedDeltaMs", wall - elapsed)
    }
}.toString()
