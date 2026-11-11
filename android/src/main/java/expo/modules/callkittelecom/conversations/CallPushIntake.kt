package expo.modules.callkittelecom.conversations

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import expo.modules.callkittelecom.CallPushJavaScript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A decoded invitation. Media credentials are kept out of public snapshots by the engine. */
data class IncomingCallPush(
    val serverCallId: String,
    val eventId: String,
    val caller: Map<String, Any?>?,
    val hasVideo: Boolean = false,
    val media: Map<String, Any?>? = null,
    val metadata: Map<String, Any?> = emptyMap(),
) {
    internal fun payload(): Map<String, Any?> = mapOf(
        "serverCallId" to serverCallId, "eventId" to eventId, "caller" to caller,
        "hasVideo" to hasVideo, "media" to media, "metadata" to metadata,
    )
}

/** An end notification for the same server identity used by the invitation. */
data class EndedCallPush(val serverCallId: String, val eventId: String, val reason: String) {
    internal fun payload(): Map<String, Any?> = mapOf(
        "serverCallId" to serverCallId, "eventId" to eventId, "reason" to reason,
    )
}

sealed interface DecodedCallPush {
    data class Incoming(val push: IncomingCallPush) : DecodedCallPush
    data class Ended(val push: EndedCallPush) : DecodedCallPush
}

/** Return null to leave the message to the application's other push handlers. */
fun interface CallPushDecoder { fun decode(data: Map<String, String>): DecodedCallPush? }

sealed interface CallPushDecision {
    data object Admit : CallPushDecision
    data class Refuse(val reason: String) : CallPushDecision
}

/** Runs before any incoming call is reported to Telecom. Keep refusal reasons free of secrets. */
fun interface CallPushAdmission { suspend fun admit(push: IncomingCallPush): CallPushDecision }

/** The library's messageType envelope, including its snake_case aliases. */
object DefaultCallPushDecoder : CallPushDecoder {
    @Suppress("UNCHECKED_CAST")
    override fun decode(data: Map<String, String>): DecodedCallPush? {
        val type = data["messageType"] ?: return null
        if (type !in setOf("incomingCall", "incoming_call", "callEnded", "call_ended")) return null
        val raw = data[type] ?: return null
        val payload = value(JSONObject(raw)) as Map<String, Any?>
        val server = payload["serverCallId"] as? String ?: return null
        val event = payload["eventId"] as? String ?: return null
        return if (type == "callEnded" || type == "call_ended") {
            DecodedCallPush.Ended(EndedCallPush(server, event, payload["reason"] as? String ?: "remoteEnded"))
        } else DecodedCallPush.Incoming(IncomingCallPush(
            server, event, payload["caller"] as? Map<String, Any?>, payload["hasVideo"] == true,
            payload["media"] as? Map<String, Any?>, payload["metadata"] as? Map<String, Any?> ?: emptyMap(),
        ))
    }

    private fun value(input: Any?): Any? = when (input) {
        JSONObject.NULL -> null
        is JSONObject -> input.keys().asSequence().associateWith { value(input.get(it)) }
        is JSONArray -> (0 until input.length()).map { value(input.get(it)) }
        else -> input
    }
}

/** Configure once from Application.onCreate; either FCM service can use this same intake. */
object CallPushIntake {
    private data class Configuration(val decoder: CallPushDecoder, val admission: CallPushAdmission)
    @Volatile private var configuration = Configuration(DefaultCallPushDecoder, CallPushAdmission { CallPushDecision.Admit })
    // Accessed only on the singleton engine's main scope, including across configure calls.
    private var handler: NativeCallPush? = null

    @JvmStatic fun configure(
        decoder: CallPushDecoder = DefaultCallPushDecoder,
        admission: CallPushAdmission = CallPushAdmission { CallPushDecision.Admit },
    ) { configuration = Configuration(decoder, admission) }

    /** True acknowledges recognition, including refusal; reporting continues on the engine's scope. */
    @JvmStatic fun handle(context: Context, message: RemoteMessage): Boolean =
        receive(message.data) { push, admission ->
            val engine = ConversationEngine.get(context)
            engine.jobs.launch {
                try {
                    val intake = handler ?: NativeCallPush(engine) { CallPushJavaScript.warm(engine.context) }
                        .also { handler = it }
                    intake.handle(push, message.originalPriority == RemoteMessage.PRIORITY_HIGH, admission)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Malformed/stale pushes never crash the service. */ }
            }
        }

    internal fun receive(data: Map<String, String>, dispatch: (DecodedCallPush, CallPushAdmission) -> Unit): Boolean {
        val current = configuration
        val push = try { current.decoder.decode(data) } catch (_: Exception) { null } ?: return false
        dispatch(push, current.admission)
        return true
    }

    /** Forward FirebaseMessagingService.onNewToken here to update the shared token snapshot. */
    @JvmStatic fun newToken(context: Context, token: String) {
        val engine = ConversationEngine.get(context)
        engine.jobs.launch { engine.updatePushToken(token) }
    }
}
