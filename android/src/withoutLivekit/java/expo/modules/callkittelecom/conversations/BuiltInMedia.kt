package expo.modules.callkittelecom.conversations

import android.content.Context

/**
 * Without `androidNativeMedia: "livekit"` there is no native media engine: native media needs a session
 * registered in NativeMediaRegistry.factories, and credentials from a registered native fetcher.
 */
internal object BuiltInMedia {
    /** The built-in session for a media adapter, or null when there is none. */
    @Suppress("UNUSED_PARAMETER")
    fun session(context: Context, adapter: String, invitationId: String): NativeMediaSession? = null
}
