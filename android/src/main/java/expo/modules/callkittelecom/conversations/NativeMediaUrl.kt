package expo.modules.callkittelecom.conversations

import java.net.URI

/** Cleartext signaling is available only to debuggable application builds. */
internal fun requireNativeMediaUrl(url: String, debuggable: Boolean) {
    val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("invalid_arguments") }
    require((uri.scheme == "wss" || debuggable && uri.scheme == "ws") &&
        uri.host != null && uri.userInfo == null) { "invalid_arguments" }
}
