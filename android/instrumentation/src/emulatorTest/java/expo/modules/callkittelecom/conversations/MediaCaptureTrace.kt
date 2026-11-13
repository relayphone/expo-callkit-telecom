package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import org.json.JSONObject

/** Bounded source-side evidence on the device monotonic clock; no PCM crosses the bridge. */
internal class MediaCaptureTrace {
    @Volatile var phase = "prejoin"
    private val lock = Any()
    private val callbacks = mutableListOf<List<Any?>>()
    private val states = mutableListOf<Map<String, Any>>()
    private val rtc = mutableListOf<Map<String, Any>>()
    private var lastState: List<Any>? = null
    private var overflow = false

    fun returned(entered: Long, first: Long, frames: Int, rate: Int, bytesRead: Int,
        muted: Boolean?, crc: Long) = synchronized(lock) {
        if (callbacks.size == 4096) overflow = true
        else callbacks += listOf(phase, entered, SystemClock.elapsedRealtimeNanos(), first, frames,
            rate, bytesRead, muted, crc, Thread.currentThread().id)
    }

    fun state(mode: Int, aec: Boolean, agc: Boolean, ns: Boolean, enabled: Boolean, muted: Boolean) = synchronized(lock) {
        val next = listOf(mode, aec, agc, ns, enabled, muted)
        if (next != lastState) {
            if (states.size == 128) overflow = true
            else states += mapOf("atNs" to SystemClock.elapsedRealtimeNanos(), "mode" to mode,
                "aec" to aec, "agc" to agc, "ns" to ns, "trackEnabled" to enabled, "publicationMuted" to muted)
            lastState = next
        }
    }

    fun snapshot(): JSONObject = synchronized(lock) { JSONObject(mapOf(
        "clock" to "android.elapsedRealtimeNanos", "overflow" to overflow,
        "columns" to listOf("phase", "enteredNs", "returnedNs", "firstFrame", "frames", "rate",
            "bytesRead", "publicationMuted", "pcmCrc32", "thread"),
        "callbacks" to callbacks.toList(), "apmStates" to states.toList(), "rtc" to rtc.toList())) }

    fun stats(values: List<Map<String, Any?>>) = synchronized(lock) {
        if (rtc.size == 128) overflow = true
        else rtc += mapOf("atNs" to SystemClock.elapsedRealtimeNanos(), "phase" to phase, "values" to values)
    }
}
