package expo.modules.callkittelecom.conversations

/** A session's transient room recovery; it does not grant new audio authority. */
enum class NativeMediaConnection { RECONNECTING, RECONNECTED }

internal fun NativeMediaStage.connection(): NativeMediaConnection? = when (this) {
    NativeMediaStage.RECONNECTING -> NativeMediaConnection.RECONNECTING
    NativeMediaStage.RECONNECTED -> NativeMediaConnection.RECONNECTED
    else -> null
}

/** Separates call connection from room recovery and eligibility for communication audio. */
internal class NativeConnectionStatus(private val direction: NativeCallDirection = NativeCallDirection.INCOMING) {
    private var reconnecting = false
    private var reportedConnected = false
    val connectsOnMedia: Boolean get() = direction == NativeCallDirection.INCOMING

    suspend fun reportConnected(activate: suspend () -> Unit) {
        activate()
        reportedConnected = true
    }

    fun canUseAudio(admitted: Boolean, active: Boolean, held: Boolean): Boolean = !held &&
        // AOSP includes DIALING in call audio focus and selects MODE_IN_COMMUNICATION for VoIP:
        // https://android.googlesource.com/platform/packages/services/Telecomm/+/e3af993ac0f53e1a0e2ee8b7be3d67cb9970a616/src/com/android/server/telecom/CallAudioManager.java#98
        // https://android.googlesource.com/platform/packages/services/Telecomm/+/e3af993ac0f53e1a0e2ee8b7be3d67cb9970a616/src/com/android/server/telecom/CallAudioModeStateMachine.java#629
        (active || direction == NativeCallDirection.OUTGOING && admitted)
    fun observe(connection: NativeMediaConnection): Boolean {
        val next = connection == NativeMediaConnection.RECONNECTING
        return (next != reconnecting).also { reconnecting = next }
    }
    fun connected(held: Boolean): String = when {
        held -> "held"
        !connectsOnMedia && !reportedConnected -> "connecting"
        reconnecting -> "reconnecting"
        else -> "connected"
    }
}
