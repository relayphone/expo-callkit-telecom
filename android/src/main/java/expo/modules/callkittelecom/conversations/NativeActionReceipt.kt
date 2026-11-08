package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class NativeActionOrigin(val wireValue: String) { APP("app"), SYSTEM("system") }

internal sealed interface NativePerformedAction {
    data object Answer : NativePerformedAction
    data object Start : NativePerformedAction
    data object End : NativePerformedAction
    data class Hold(val held: Boolean) : NativePerformedAction
    data class Mute(val muted: Boolean) : NativePerformedAction

    fun fields(): Map<String, Any> = when (this) {
        Answer -> mapOf("kind" to "answer")
        Start -> mapOf("kind" to "start")
        End -> mapOf("kind" to "end")
        is Hold -> mapOf("kind" to "hold", "held" to held)
        is Mute -> mapOf("kind" to "mute", "muted" to muted)
    }
}

/** One main-confined native action, including its app/system callback echoes. */
internal class NativeActionReceipt(
    private val conversationId: String,
    private val origin: NativeActionOrigin,
    private val action: NativePerformedAction,
) {
    private var claimed = false

    suspend fun perform(effect: suspend () -> Unit, publishSnapshot: () -> Unit,
                        emit: (String, Map<String, Any?>) -> Unit) {
        if (claimed) return
        claimed = true
        effect()
        currentCoroutineContext().ensureActive()
        publishSnapshot()
        currentCoroutineContext().ensureActive()
        emit("conversationEvent", mapOf("type" to "actionPerformed", "payload" to mapOf(
            "conversationId" to conversationId, "origin" to origin.wireValue, "action" to action.fields())))
    }
}
