package expo.modules.callkittelecom.conversations

import android.content.Context
import org.json.JSONObject

/**
 * Without `mediaAdapter: "livekit"` there is no native media engine: native media needs a session
 * registered in NativeMediaRegistry.factories, and credentials from a registered native fetcher.
 */
internal object BuiltInMedia {
    /** The built-in session for a media adapter, or null when there is none. */
    @Suppress("UNUSED_PARAMETER")
    fun session(context: Context, adapter: String, invitationId: String): NativeMediaSession? = null

    /** Fetches media credentials from the configured HTTPS endpoint. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchCredentials(payload: Map<String, Any?>, source: JSONObject): Map<String, Any?> =
        error("The built-in HTTPS credentials fetcher needs mediaAdapter: \"livekit\"")
}
