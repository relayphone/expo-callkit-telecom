package expo.modules.callkittelecom.conversations

import java.util.concurrent.ConcurrentHashMap

/** The application reads its native-held authentication; this must not wait for JavaScript. */
data class NativeMediaAuthentication(val bearer: String, val deviceId: String)

/**
 * Register during Application.onCreate. The built-in HTTPS credentials fetcher, which
 * `mediaAdapter: "livekit"` adds, authenticates with these providers.
 */
object NativeMediaAuthenticationProviders {
    val providers = ConcurrentHashMap<String, suspend () -> NativeMediaAuthentication>()
}

class MediaCredentialsUnavailable(val status: Int) : Exception("Media credentials unavailable")
