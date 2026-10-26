package expo.modules.callkittelecom.conversations

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*

/** All mutable state and Core-Telecom controls are confined to Dispatchers.Main. */
class ConversationEngine(val context: Context) {
    companion object {
        @Volatile private var instance: ConversationEngine? = null
        fun get(context: Context): ConversationEngine = instance ?: synchronized(this) {
            instance ?: ConversationEngine(context.applicationContext).also { instance = it }
        }
    }
    val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var emit: (String, Map<String, Any?>) -> Unit = { _, _ -> }
    private val telecom = CallsManager(context).apply {
        registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
    }
    private class Call(val state: MutableMap<String, Any?>) {
        val ready = CompletableDeferred<CallControlScope>()
        var job: Job? = null
        var endpoints: List<CallEndpointCompat> = emptyList()
    }
    private val calls = linkedMapOf<String, Call>()
    private val recent = mutableMapOf<String, Pair<Long, String>>()
    private var revision = 0L
    @Volatile var snapshot: Map<String, Any?> = mapOf("revision" to 0, "conversations" to emptyList<Any>())
        private set
    private var routeRevision = 0L
    private var routes: Map<String, Any?> = mapOf("revision" to 0, "routes" to emptyList<Any>(), "current" to null)
    val actions = ActionCoordinator({ name, body -> emit(name, body) }, ::publish)
    private val audio = context.getSystemService(AudioManager::class.java)

    fun audioRoutes() = routes
    private fun publish() {
        snapshot = mapOf("revision" to ++revision, "conversations" to calls.map { (id, call) ->
            call.state.filterKeys { it != "endReason" } + ("pending" to actions.pending(id)) }.sortedBy { it["id"] as String })
        emit("conversationsChanged", snapshot)
    }
    private fun call(id: String) = calls[id] ?: error("unknown_conversation")
    private suspend fun control(id: String) = call(id).ready.await()
    private fun checked(result: CallControlResult) {
        if (result is CallControlResult.Error) error("Telecom control failed: ${result.errorCode}")
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun incoming(payload: Map<String, Any?>): Map<String, Any?> {
        val eventId = payload["eventId"] as? String ?: return failure("invalid_arguments")
        val serverId = payload["serverCallId"] as? String ?: return failure("invalid_arguments")
        val caller = payload["caller"] as? Map<String, Any?> ?: return failure("invalid_arguments")
        if (eventId.isBlank() || serverId.isBlank() || (caller["id"] as? String).isNullOrBlank()) return failure("invalid_arguments")
        val now = android.os.SystemClock.elapsedRealtime()
        recent.entries.removeAll { now - it.value.first > 120_000 }
        val duplicate = recent["event:$eventId"] ?: recent["call:$serverId"]
        if (duplicate != null) return mapOf("status" to "reported", "id" to duplicate.second)
        val id = CallIdentity.forServerCallId(serverId).toString()
        if (calls.containsKey(id)) { control(id); return mapOf("status" to "reported", "id" to id) }
        return try {
            add(id, caller, payload["hasVideo"] == true, payload)
            recent["event:$eventId"] = now to id
            recent["call:$serverId"] = now to id
            mapOf("status" to "reported", "id" to id)
        } catch (e: CallException) {
            if (e.code == CallException.ERROR_CALL_NOT_PERMITTED_AT_PRESENT_TIME) mapOf("status" to "atCapacity")
            else failure("conversation", e.code.toString())
        } catch (e: Exception) { failure("conversation") }
    }
    private fun failure(code: String, nativeCode: String? = null) = mapOf("status" to "failed",
        "error" to mapOf("code" to code, "message" to "Incoming report failed", "nativeCode" to nativeCode))

    suspend fun start(options: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST") val to = options["to"] as? Map<String, Any?> ?: error("invalid_arguments")
        require(!(to["id"] as? String).isNullOrBlank()) { "invalid_arguments" }
        val id = UUID.randomUUID().toString()
        add(id, to, options["video"] == true, null)
        try { action(id, "start", "app") } catch (e: Exception) { finish(id, "failed"); throw e }
        return id
    }
    private suspend fun add(id: String, member: Map<String, Any?>, video: Boolean, payload: Map<String, Any?>?) {
        val incoming = payload != null
        val call = Call(linkedMapOf("id" to id, "direction" to if (incoming) "incoming" else "outgoing",
            "status" to if (incoming) "ringing" else "dialing", "members" to listOf(member),
            "isMuted" to false, "isVideo" to video, "payload" to payload,
            "capabilities" to mapOf("hold" to true, "merge" to false, "tones" to true, "video" to true)))
        calls[id] = call
        publish()
        val handle = (member["phoneNumber"] ?: member["email"] ?: member["id"]).toString()
        val attributes = CallAttributesCompat((member["displayName"] ?: handle).toString(), Uri.fromParts("sip", handle, null),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE)
        call.job = jobs.launch {
            try {
                telecom.addCall(attributes,
                    onAnswer = { action(id, "join", "system"); joined(id) },
                    onDisconnect = { action(id, "end", "system"); terminal(id, "local") },
                    onSetActive = { hold(id, false, "system") },
                    onSetInactive = { hold(id, true, "system") }) {
                    call.ready.complete(this)
                    ConversationService.show(context, id, call.state)
                    launch { availableEndpoints.collect { call.endpoints = it; updateRoutes(id, null) } }
                    launch { currentCallEndpoint.collect { updateRoutes(id, it) } }
                    launch { isMuted.collect { muted ->
                        if (calls[id] === call && call.state["isMuted"] != muted) mute(id, muted, "system")
                    } }
                }
            } catch (e: Exception) { call.ready.completeExceptionally(e) }
            finally {
                currentCoroutineContext().cancelChildren()
                if (calls[id] === call) terminal(id, call.state["endReason"] as? String ?: "failed")
            }
        }
        withTimeout(6000) { call.ready.await() }
    }

    private suspend fun action(id: String, kind: String, origin: String, args: Map<String, Any?> = emptyMap()) {
        val current = call(id)
        actions.perform(current.state.toMap(), kind, origin, args)
        check(calls[id] === current) { "unknown_conversation" }
    }
    suspend fun join(id: String) {
        action(id, "join", "app")
        checked(control(id).answer(if (call(id).state["isVideo"] == true) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL))
        joined(id)
    }
    private fun joined(id: String) { call(id).state["status"] = "connecting"; publish() }
    suspend fun end(id: String) { action(id, "end", "app"); finish(id, "local") }
    suspend fun hold(id: String, held: Boolean, origin: String = "app") {
        val current = call(id)
        if ((current.state["status"] == "held") == held) return
        action(id, "hold", origin, mapOf("held" to held))
        if (origin == "app") checked(if (held) control(id).setInactive() else control(id).setActive())
        current.state["status"] = if (held) "held" else if (current.state["connectedAt"] != null) "connected" else "connecting"
        publish()
    }
    suspend fun mute(id: String, muted: Boolean, origin: String = "app") {
        val current = call(id)
        if (current.state["isMuted"] == muted) return
        action(id, "mute", origin, mapOf("muted" to muted))
        audio.isMicrophoneMute = muted
        current.state["isMuted"] = muted
        publish()
    }
    suspend fun tones(id: String, digits: String) {
        require(digits.matches(Regex("[0-9*#ABCDabcd]+"))) { "invalid_arguments" }
        action(id, "tones", "app", mapOf("digits" to digits))
    }
    suspend fun report(id: String, report: Map<String, Any?>) {
        val current = call(id)
        when (report["type"]) {
            "connecting" -> if (current.state["status"] in listOf("dialing", "connecting")) current.state["status"] = "connecting"
            "connected" -> {
                if (current.state["status"] != "held") { checked(control(id).setActive()); current.state["status"] = "connected" }
                current.state["connectedAt"] = current.state["connectedAt"] ?: Instant.now().toString()
            }
            "updated" -> { report["members"]?.let { current.state["members"] = it }; report["video"]?.let { current.state["isVideo"] = it } }
            "ended" -> { finish(id, report["reason"] as? String ?: error("invalid_arguments")); return }
            else -> error("invalid_arguments")
        }
        publish()
    }
    private suspend fun finish(id: String, reason: String) {
        val cause = when (reason) { "local", "failed" -> DisconnectCause.LOCAL; "unanswered" -> DisconnectCause.MISSED; else -> DisconnectCause.REMOTE }
        call(id).state["endReason"] = reason
        checked(control(id).disconnect(DisconnectCause(cause)))
        if (calls.containsKey(id)) terminal(id, reason)
    }
    private fun terminal(id: String, reason: String) {
        val current = calls[id] ?: return
        current.state["status"] = "ended"
        current.state["ended"] = mapOf("at" to Instant.now().toString(), "reason" to reason)
        actions.cancel(id)
        publish()
        calls.remove(id)
        ConversationService.remove(context, id)
        publish()
    }
    private fun route(endpoint: CallEndpointCompat) = mapOf("id" to endpoint.identifier.toString(), "name" to endpoint.name.toString(),
        "type" to when(endpoint.type) { CallEndpointCompat.TYPE_EARPIECE -> "builtInReceiver"; CallEndpointCompat.TYPE_SPEAKER -> "builtInSpeaker";
            CallEndpointCompat.TYPE_BLUETOOTH -> "bluetoothHFP"; CallEndpointCompat.TYPE_WIRED_HEADSET -> "headphones"; else -> "unknown" })
    private fun updateRoutes(id: String, endpoint: CallEndpointCompat?) {
        if (!calls.containsKey(id)) return
        routes = mapOf("revision" to ++routeRevision, "routes" to call(id).endpoints.map(::route),
            "current" to (endpoint?.let(::route) ?: routes["current"]))
        emit("audioRoutesChanged", routes)
    }
    suspend fun selectRoute(id: String) {
        val current = calls.values.firstOrNull { it.state["status"] == "connected" } ?: error("unknown_conversation")
        val endpoint = current.endpoints.firstOrNull { it.identifier.toString() == id } ?: error("invalid_arguments")
        checked(current.ready.await().requestEndpointChange(endpoint))
    }
}
