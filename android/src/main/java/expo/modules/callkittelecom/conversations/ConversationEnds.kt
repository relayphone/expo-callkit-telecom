package expo.modules.callkittelecom.conversations

import android.content.Context
import kotlinx.coroutines.*

/** Reports server call ends learned by the app, using the same ownership as a callEnded push. */
object ConversationEnds {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * End [serverCallId] with `remoteEnded`, `answeredElsewhere`, `declinedElsewhere`,
     * `unanswered` or `failed`. Other reasons become `failed`. Safe from any thread;
     * duplicate reports and pushes share the engine's retained server call ownership.
     */
    @JvmStatic fun report(context: Context, serverCallId: String, reason: String) {
        val application = context.applicationContext
        scope.launch {
            ConversationEndsRouter(ConversationEngine.get(application)::remoteEnd).report(serverCallId, reason)
        }
    }
}

/** Keeps local reason defaults outside the push wire decoder and preserves server identity bytes. */
internal class ConversationEndsRouter(private val remoteEnd: (Map<String, Any?>) -> Unit) {
    fun report(serverCallId: String, reason: String) {
        remoteEnd(NativeCallEnded.reported(serverCallId, reason))
    }
}
