package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal const val NATIVE_MEDIA_RECONNECT_TIMEOUT_MILLIS = 30_000L
internal enum class NativeRoomState { RECONNECTING, RECONNECTED, DISCONNECTED }

/** Session-owned connection observation; recovery neither reconnects audio nor changes call status. */
internal class NativeRoomConnection(
    scope: CoroutineScope,
    private val signal: (NativeMediaStage) -> Unit,
    private val disconnected: () -> Unit,
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
                    signal(NativeMediaStage.RECONNECTING)
                    deadline = work.launch {
                        wait(reconnectTimeoutMillis)
                        disconnect()
                    }
                }
                NativeRoomState.RECONNECTED -> if (deadline != null) {
                    deadline?.cancel(); deadline = null
                    signal(NativeMediaStage.RECONNECTED)
                }
                NativeRoomState.DISCONNECTED -> disconnect()
            }
        } }
    }

    private fun disconnect() {
        if (closed) return
        closed = true
        deadline?.cancel(); deadline = null
        disconnected()
        lifetime.cancel()
    }

    fun close() {
        closed = true
        deadline?.cancel(); deadline = null
        lifetime.cancel()
    }
}
