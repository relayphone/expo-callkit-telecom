package expo.modules.callkittelecom.conversations

import android.content.Context
import kotlinx.coroutines.*

/** Options captured by each new native media session. Configure during Application.onCreate. */
data class NativeMediaOptions(
    /** Opt in for VPNs without a default route; disabling monitoring can slow network handover. */
    val disableNetworkMonitor: Boolean = false,
)

/** Native LiveKit credentials for a call created by the app or a ringing incoming call. */
object ConversationMedia {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile internal var options = NativeMediaOptions()
        private set

    /** Apply options to sessions created after this call. Existing sessions retain their options. */
    @JvmStatic fun configure(options: NativeMediaOptions) { this.options = options }

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
