package expo.modules.callkittelecom.conversations

import java.net.URI
import java.net.URLEncoder

internal data class FixtureMediaAuthentication(val bearer: String, val deviceId: String)

/** Instrumentation-only credentials for the independent media server and its failure controls. */
internal class FixtureMediaCredentials(
    private val authenticate: suspend () -> FixtureMediaAuthentication,
    private val http: NativeMediaHttp = NativeMediaHttp(),
) {
    suspend fun fetch(payload: Map<String, Any?>, baseUrl: String): Map<String, Any?> {
        val base = URI(baseUrl)
        require(base.scheme == "https" && base.host != null && base.rawUserInfo == null &&
            base.rawQuery == null && base.rawFragment == null) { "Invalid credentials origin" }
        val authentication = authenticate()
        require(authentication.bearer.isNotBlank() && authentication.deviceId.isNotBlank())
        val serverId = payload["serverCallId"] as? String ?: error("Invalid media invitation")
        val eventId = payload["eventId"] as? String ?: error("Invalid media invitation")
        val encoded = URLEncoder.encode(serverId, "UTF-8").replace("+", "%20")
        val url = base.toString().trimEnd('/') + "/v1/calls/$encoded/media-credentials"
        return http.post(url, authentication.bearer, mapOf("eventId" to eventId, "deviceId" to authentication.deviceId))
    }
}
