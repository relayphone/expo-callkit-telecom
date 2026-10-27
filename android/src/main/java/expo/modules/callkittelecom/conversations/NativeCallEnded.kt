package expo.modules.callkittelecom.conversations

import android.os.Build
import android.os.SystemClock
import android.telecom.DisconnectCause

/** Strong host input; wire compatibility defaults are applied before this boundary. */
internal data class NativeCallEnded(val eventId: String, val serverCallId: String, val reason: String) {
    fun disconnectCause(sdkInt: Int = Build.VERSION.SDK_INT): DisconnectCause {
        val code = when (reason) {
            // API34+ CallControl validates four codes before IPC. Legacy Connection does not.
            "answeredElsewhere" -> if (sdkInt >= 34) DisconnectCause.REMOTE else DisconnectCause.ANSWERED_ELSEWHERE
            "unanswered" -> DisconnectCause.MISSED
            else -> DisconnectCause.REMOTE
        }
        return DisconnectCause(code, reason)
    }

    companion object {
        private val remoteReasons = setOf("remoteEnded", "failed", "unanswered", "answeredElsewhere", "declinedElsewhere")
        fun typed(raw: Map<String, Any?>): NativeCallEnded? {
            val event = raw["eventId"] as? String ?: error("invalid_arguments")
            val server = raw["serverCallId"] as? String ?: error("invalid_arguments")
            val reason = raw["reason"] as? String ?: error("invalid_arguments")
            require(reason in remoteReasons) { "invalid_arguments" }
            if (!validIdentifier(event) || !validIdentifier(server)) return null
            return NativeCallEnded(event, server, reason)
        }
        fun wire(raw: Map<String, Any?>): NativeCallEnded? = runCatching { typed(raw +
            ("reason" to ((raw["reason"] as? String)?.takeIf { it in remoteReasons } ?: "remoteEnded"))) }.getOrNull()

        // Foundation whitespacesAndNewlines; validation never changes identity bytes.
        fun validIdentifier(value: String): Boolean = value.trim { code -> when (code.code) {
            in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680, in 0x2000..0x200B,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        } }.isNotEmpty()
    }
}

/** Main-confined intake ownership survives pending admission and native call removal. */
internal class NativeEndedIntake(private val now: () -> Long = SystemClock::elapsedRealtime) {
    class Intent(val payload: NativeCallEnded) {
        var emitted = false
    }
    class Entry(val id: String, val serverCallId: String, var retainedUntil: Long) {
        var pending = true
        var live = true
        var admitted = false
        var intent: Intent? = null
    }
    private val entries = mutableMapOf<String, Entry>()
    private val events = mutableMapOf<String, Long>()
    private fun prune(time: Long) {
        events.entries.removeAll { time - it.value > 120_000 }
        entries.entries.removeAll { !it.value.live && !it.value.pending && it.value.retainedUntil < time }
    }
    fun reserve(server: String, id: String): Entry {
        val time = now(); prune(time)
        return entries.getOrPut(server) { Entry(id, server, time + 120_000) }.also {
            it.pending = true; it.live = true
            it.retainedUntil = maxOf(it.retainedUntil, time + 120_000)
        }
    }
    fun accept(payload: NativeCallEnded): Entry? {
        val time = now(); prune(time)
        if (events.containsKey(payload.eventId)) return null
        // Unknown notifications consume only their event ID, never a future invitation.
        events[payload.eventId] = time
        return entries[payload.serverCallId]?.also { it.intent = Intent(payload) }
    }
    fun admitted(entry: Entry) { entry.admitted = true }
    fun settled(entry: Entry) { entry.pending = false; discardUnreported(entry) }
    fun removed(entry: Entry) {
        entry.live = false
        entry.retainedUntil = maxOf(entry.retainedUntil, now() + 120_000)
        discardUnreported(entry)
    }
    private fun discardUnreported(entry: Entry) {
        if (!entry.live && !entry.pending && !entry.admitted && current(entry)) entries.remove(entry.serverCallId)
    }
    fun current(entry: Entry): Boolean = entries[entry.serverCallId] === entry
}
