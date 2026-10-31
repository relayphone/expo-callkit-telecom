package expo.modules.callkittelecom.conversations

import android.content.Context
import org.json.JSONObject

/**
 * The native media engine that `mediaAdapter: "livekit"` adds: LiveKit and raw WebRTC sessions on
 * livekit-android and its WebRTC build, and the built-in HTTPS credentials fetcher.
 */
internal object BuiltInMedia {
    /** The built-in session for a media adapter, or null when there is none. */
    fun session(context: Context, adapter: String, invitationId: String): NativeMediaSession? = when (adapter) {
        "livekit" -> LiveKitNativeSession(context, invitationId)
        "webrtc" -> WebRTCNativeSession(context, invitationId)
        else -> null
    }

    /** Fetches media credentials from the configured HTTPS endpoint. */
    suspend fun fetchCredentials(payload: Map<String, Any?>, source: JSONObject): Map<String, Any?> =
        NativeHttpCredentials().fetch(payload, source)
}
