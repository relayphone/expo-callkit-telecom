package expo.modules.callkittelecom.conversations

import io.livekit.android.events.DisconnectReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal const val NATIVE_MEDIA_RECONNECT_TIMEOUT_MILLIS = 30_000L
internal sealed interface NativeRoomState {
    data object RECONNECTING : NativeRoomState
    data object RECONNECTED : NativeRoomState
    data class DISCONNECTED(val reason: DisconnectReason) : NativeRoomState
}

internal fun DisconnectReason.disconnection(): NativeMediaDisconnection = when (this) {
    DisconnectReason.ROOM_DELETED, DisconnectReason.PARTICIPANT_REMOVED -> NativeMediaDisconnection.REMOTE_ENDED
    DisconnectReason.CLIENT_INITIATED -> NativeMediaDisconnection.CLIENT_INITIATED
    else -> NativeMediaDisconnection.FAILURE
}

/** Session-owned room observation; the call owner maps recovery without restarting audio. */
internal class NativeRoomConnection(
    scope: CoroutineScope,
    private val signal: (NativeMediaStage, String?) -> Unit,
    private val disconnected: (DisconnectReason?) -> Unit,
    private val reconnectTimeoutMillis: Long = NATIVE_MEDIA_RECONNECT_TIMEOUT_MILLIS,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + lifetime)
    private var deadline: Job? = null
    private var closed = false

    fun observe(events: Flow<NativeRoomState>) {
        work.launch(start = CoroutineStart.UNDISPATCHED) { events.collect { event ->
            if (!closed) when (event) {
                NativeRoomState.RECONNECTING -> if (deadline == null) {
                    signal(NativeMediaStage.RECONNECTING, null)
                    deadline = work.launch {
                        wait(reconnectTimeoutMillis)
                        disconnect(null)
                    }
                }
                NativeRoomState.RECONNECTED -> if (deadline != null) {
                    deadline?.cancel(); deadline = null
                    signal(NativeMediaStage.RECONNECTED, null)
                }
                is NativeRoomState.DISCONNECTED -> {
                    signal(NativeMediaStage.ROOM_DISCONNECTED, event.reason.name)
                    disconnect(event.reason)
                }
            }
        } }
    }

    private fun disconnect(reason: DisconnectReason?) {
        if (closed) return
        closed = true
        // A recovery deadline is a media failure with its own diagnostic reason.
        if (reason == null) signal(NativeMediaStage.ROOM_DISCONNECTED, "RECONNECT_TIMEOUT")
        deadline?.cancel(); deadline = null
        disconnected(reason)
        lifetime.cancel()
    }

    fun close(reason: DisconnectReason? = null) {
        if (closed) return
        closed = true
        if (reason != null) signal(NativeMediaStage.ROOM_DISCONNECTED, reason.name)
        deadline?.cancel(); deadline = null
        lifetime.cancel()
    }

    /** Preserve a reason already emitted by LiveKit before recording our own release, including cancellation cleanup. */
    suspend fun closeForRelease() = withContext(NonCancellable) {
        yield()
        close(DisconnectReason.CLIENT_INITIATED)
    }
}
