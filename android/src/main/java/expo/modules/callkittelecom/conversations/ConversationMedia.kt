package expo.modules.callkittelecom.conversations

import android.content.Context
import kotlinx.coroutines.*

/** Native LiveKit credentials for a call created by the app or a ringing incoming call. */
object ConversationMedia {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Supply push-shaped LiveKit [media] with `androidNativeMedia = "livekit"`.
     * Outgoing calls join natively; ringing calls retain credentials until answer. Unknown,
     * ended or already-owned sessions are ignored with a MEDIA_IGNORED signal. Credentials
     * are never published. Safe to call from any thread; delivery is serialized on main.
     */
    @JvmStatic fun provide(context: Context, conversationId: String, media: Map<String, Any?>) {
        val application = context.applicationContext
        val credentials = media.toMap()
        scope.launch { ConversationEngine.get(application).provideMedia(conversationId, credentials) }
    }
}
