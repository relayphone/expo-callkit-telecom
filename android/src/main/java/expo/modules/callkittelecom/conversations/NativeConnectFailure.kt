package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.CancellationException

/** JOIN_FAILURE precedes the connect exception; classify it before retiring its owner. */
internal class NativeConnectFailure(
    private val preparationFailed: (NativePrewarmFailure) -> Unit,
    private val disconnected: () -> Unit,
) {
    private var connecting = true
    private var joinFailed = false

    fun deferJoinFailure(): Boolean {
        if (!connecting) return false
        joinFailed = true
        return true
    }

    fun connected() { connecting = false }

    fun failed(stage: NativePrewarmStage, error: Throwable) {
        connecting = false
        if (!joinFailed || error is CancellationException) return
        joinFailed = false
        preparationFailed(NativePrewarmFailure(stage, error.javaClass.simpleName.ifEmpty { error.javaClass.name }))
        disconnected()
    }
}
