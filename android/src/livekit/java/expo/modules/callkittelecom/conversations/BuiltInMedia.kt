package expo.modules.callkittelecom.conversations

import android.content.Context

/**
 * The native media engine that `androidNativeMedia: "livekit"` adds: LiveKit and raw WebRTC sessions on
 * livekit-android and its WebRTC build.
 */
internal object BuiltInMedia {
    /** The built-in session for a media adapter, or null when there is none. */
    fun session(context: Context, adapter: String, invitationId: String): NativeMediaSession? = when (adapter) {
        "livekit" -> LiveKitNativeSession(context, invitationId)
        "webrtc" -> WebRTCNativeSession(context, invitationId)
        else -> null
    }
}
