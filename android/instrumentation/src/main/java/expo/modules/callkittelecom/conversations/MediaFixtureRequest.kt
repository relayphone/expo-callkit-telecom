package expo.modules.callkittelecom.conversations

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Use the real cancellable HTTP path for both qualification and poll-phase diagnostics. */
internal suspend fun readMediaFixture(client: Call.Factory, request: Request,
    onPhase: (MediaReceiverHttpPhase) -> Unit = {}): JSONObject = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    onPhase(MediaReceiverHttpPhase.ENQUEUED)
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use {
                if (!continuation.isActive) return
                onPhase(MediaReceiverHttpPhase.HEADERS)
                check(it.isSuccessful) { "Media fixture request failed" }
                onPhase(MediaReceiverHttpPhase.BODY)
                JSONObject(it.body!!.string()).also { onPhase(MediaReceiverHttpPhase.PARSED) }
            } }
            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}
