package expo.modules.callkittelecom.conversations

import android.content.Context

/** The built-in LiveKit session selected by `androidNativeMedia: "livekit"`. */
internal object BuiltInMedia {
    /** The built-in session for a media adapter, or null when there is none. */
    fun session(context: Context, adapter: String, invitationId: String): NativeMediaSession? = when (adapter) {
        "livekit" -> LiveKitNativeSession(context, invitationId)
        else -> null
    }
}
