package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*

internal enum class NativeActionOrigin(val wireValue: String) { APP("app"), SYSTEM("system") }

internal sealed interface NativePerformedAction {
    data object Answer : NativePerformedAction
    data object Start : NativePerformedAction
    data object End : NativePerformedAction
    data class Hold(val held: Boolean) : NativePerformedAction
    data class Mute(val muted: Boolean) : NativePerformedAction
    data class Tones(val digits: String) : NativePerformedAction

    fun fields(): Map<String, Any> = when (this) {
        Answer -> mapOf("kind" to "answer")
        Start -> mapOf("kind" to "start")
        End -> mapOf("kind" to "end")
        is Hold -> mapOf("kind" to "hold", "held" to held)
        is Mute -> mapOf("kind" to "mute", "muted" to muted)
        is Tones -> mapOf("kind" to "tones", "digits" to digits)
    }
}

/** One main-confined native action, including its app/system callback echoes. */
internal class NativeActionReceipt(
    private val conversationId: String,
    private val origin: NativeActionOrigin,
    private val action: NativePerformedAction,
    private val serverCallId: String? = null,
    private val decline: Boolean = false,
    val controlRevision: Long = 0L,
) {
    private var claimed = false
    private var decision: CompletableDeferred<ConversationActionResult?>? = null
    private var observedOrigin: NativeActionOrigin? = null
    private var users = 0
    fun retain() { users++ }
    fun release(): Int { check(users > 0); return --users }
    fun observedFromSystem() { observedOrigin = NativeActionOrigin.SYSTEM }
    internal val conversationAction: ConversationAction? get() = when (action) {
        NativePerformedAction.Answer -> ConversationAction.Answer(conversationId, serverCallId, origin.wireValue)
        NativePerformedAction.End -> if (decline) ConversationAction.Decline(conversationId, serverCallId, origin.wireValue)
            else ConversationAction.End(conversationId, serverCallId, origin.wireValue)
        is NativePerformedAction.Hold -> ConversationAction.Hold(conversationId, serverCallId, origin.wireValue, action.held)
        is NativePerformedAction.Mute -> ConversationAction.Mute(conversationId, serverCallId, origin.wireValue, action.muted)
        NativePerformedAction.Start, is NativePerformedAction.Tones -> null
    }

    /** Memoize before suspending so an app request and its system echo share the same decision. */
    suspend fun decide(runner: ConversationActionDecision): ConversationActionResult? {
        decision?.let { return it.await() }
        val pending = CompletableDeferred<ConversationActionResult?>()
        decision = pending
        try {
            val handler = ConversationActions.handler
            val action = conversationAction
            val result = if (handler != null && action != null) runner.ask(action, handler) else null
            pending.complete(result)
            return result
        } catch (error: Throwable) { pending.completeExceptionally(error); throw error }
    }

    suspend fun perform(effect: suspend () -> Unit, publishSnapshot: () -> Unit,
                        emit: (String, Map<String, Any?>) -> Unit) = performWhile({ true }, effect, publishSnapshot, emit)

    suspend fun performWhile(stillCurrent: () -> Boolean, effect: suspend () -> Unit, publishSnapshot: () -> Unit,
                             emit: (String, Map<String, Any?>) -> Unit) {
        if (claimed) return
        claimed = true
        effect()
        currentCoroutineContext().ensureActive()
        if (!stillCurrent()) return
        publishSnapshot()
        currentCoroutineContext().ensureActive()
        emit("conversationEvent", mapOf("type" to "actionPerformed", "payload" to mapOf(
            "conversationId" to conversationId, "origin" to (observedOrigin ?: origin).wireValue, "action" to action.fields())))
    }
}
