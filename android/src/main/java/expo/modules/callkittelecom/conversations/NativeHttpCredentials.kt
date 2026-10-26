package expo.modules.callkittelecom.conversations

import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The application reads its native-held authentication; this must not wait for JavaScript. */
data class NativeMediaAuthentication(val bearer: String, val deviceId: String)

object NativeMediaAuthenticationProviders {
    val providers = ConcurrentHashMap<String, suspend () -> NativeMediaAuthentication>()
}

class MediaCredentialsUnavailable(val status: Int) : Exception("Media credentials unavailable")

/** Built-in HTTPS fetcher. No redirects, credentials in URLs, or credential logging. */
internal class NativeHttpCredentials(private val client: OkHttpClient = defaultClient) {
    companion object {
    private val defaultClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).build()
    }

    suspend fun fetch(payload: Map<String, Any?>, config: JSONObject): Map<String, Any?> {
        val base = URI(config.getString("baseUrl"))
        require(base.scheme == "https" && base.host != null && base.rawUserInfo == null &&
            base.rawQuery == null && base.rawFragment == null) { "Invalid credentials origin" }
        val authentication = NativeMediaAuthenticationProviders.providers[config.getString("authentication")]
            ?.invoke() ?: error("Native media authentication unavailable")
        require(authentication.bearer.isNotBlank() && authentication.deviceId.isNotBlank())
        val serverId = payload["serverCallId"] as? String ?: error("Invalid media invitation")
        val eventId = payload["eventId"] as? String ?: error("Invalid media invitation")
        val encoded = URLEncoder.encode(serverId, "UTF-8").replace("+", "%20")
        val url = base.toString().trimEnd('/') + "/v1/calls/$encoded/media-credentials"
        return post(url, authentication.bearer, mapOf("eventId" to eventId, "deviceId" to authentication.deviceId))
    }

    suspend fun post(url: String, bearer: String, values: Map<String, Any?>): Map<String, Any?> {
        require(URI(url).let { it.scheme == "https" && it.host != null && it.rawUserInfo == null &&
            it.rawQuery == null && it.rawFragment == null }) { "Invalid media origin" }
        val body = JSONObject(values).toString()
        require(body.toByteArray(Charsets.UTF_8).size <= 65_536) { "Media request too large" }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(Exception("Media credentials request failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) throw MediaCredentialsUnavailable(it.code)
                        val bytes = it.peekBody(65_537).bytes()
                        require(bytes.size <= 65_536) { "Media credentials response too large" }
                        val json = JSONObject(String(bytes, Charsets.UTF_8))
                        json.keys().asSequence().associateWith { key -> value(json.opt(key)) }
                    } }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }
    private fun value(input: Any?): Any? = when (input) {
        JSONObject.NULL -> null
        is JSONObject -> input.keys().asSequence().associateWith { value(input.get(it)) }
        is JSONArray -> (0 until input.length()).map { value(input.get(it)) }
        else -> input
    }
}
