package expo.modules.callkittelecom.conversations

/** The room's reconnect window survives hold without replacing the call's connection timestamp. */
internal class NativeConnectionStatus {
    private var reconnecting = false
    fun observe(stage: NativeMediaStage): Boolean {
        val next = when (stage) {
            NativeMediaStage.RECONNECTING -> true
            NativeMediaStage.RECONNECTED -> false
            else -> return false
        }
        return (next != reconnecting).also { reconnecting = next }
    }
    fun connected(held: Boolean): String = when {
        held -> "held"
        reconnecting -> "reconnecting"
        else -> "connected"
    }
}
