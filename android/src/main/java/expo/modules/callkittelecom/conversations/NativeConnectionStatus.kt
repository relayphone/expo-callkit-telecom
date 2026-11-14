package expo.modules.callkittelecom.conversations

/** A session's transient room recovery; it does not grant new audio authority. */
enum class NativeMediaConnection { RECONNECTING, RECONNECTED }

internal fun NativeMediaStage.connection(): NativeMediaConnection? = when (this) {
    NativeMediaStage.RECONNECTING -> NativeMediaConnection.RECONNECTING
    NativeMediaStage.RECONNECTED -> NativeMediaConnection.RECONNECTED
    else -> null
}

/** The room's reconnect window survives hold without replacing the call's connection timestamp. */
internal class NativeConnectionStatus {
    private var reconnecting = false
    fun observe(connection: NativeMediaConnection): Boolean {
        val next = connection == NativeMediaConnection.RECONNECTING
        return (next != reconnecting).also { reconnecting = next }
    }
    fun connected(held: Boolean): String = when {
        held -> "held"
        reconnecting -> "reconnecting"
        else -> "connected"
    }
}
