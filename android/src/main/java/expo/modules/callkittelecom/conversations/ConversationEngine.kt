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
    var bridgeOwner: Any? = null
        private set
    fun bind(owner: Any?, sender: (String, Map<String, Any?>) -> Unit) {
        actions.resetBridge()
        bridgeOwner = owner
        emit = sender
    }
    private var tokenRevision = 0L
    private var pushToken: String? = null
    fun pushTokenSnapshot(): Map<String, Any?> = mapOf("revision" to tokenRevision, "token" to pushToken?.let { mapOf("type" to "fcm", "value" to it) })
    fun updatePushToken(value: String?) { if (pushToken != value) { pushToken = value; tokenRevision++; emit("pushTokenUpdated", pushTokenSnapshot()) } }
    suspend fun remoteEnd(payload: Map<String, Any?>) {
        val serverId = payload["serverCallId"] as? String ?: return
        val id = CallIdentity.forServerCallId(serverId).toString()
        val reason = (payload["reason"] as? String)?.takeIf { it in setOf("remoteEnded", "failed", "unanswered", "answeredElsewhere", "declinedElsewhere") } ?: "remoteEnded"
        if (calls.containsKey(id)) finish(id, reason)
    }
    private val telecom = CallsManager(context).apply {
        registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
    }
    private val metadata = context.packageManager.getApplicationInfo(context.packageName,
        android.content.pm.PackageManager.GET_META_DATA).metaData
    var policy = org.json.JSONObject(metadata?.getString("ExpoCallKitTelecomJoinPolicy") ?: "{}")
    private class Call(val state: MutableMap<String, Any?>) {
        val ready = CompletableDeferred<CallControlScope>()
        var job: Job? = null
        var collectors: Job? = null
        var active = false
        var media: NativeMediaController? = null
        var timeout: Job? = null
        var endpoints: List<CallEndpointCompat> = emptyList()
    }
    private val calls = linkedMapOf<String, Call>()
    private val recent = mutableMapOf<String, Pair<Long, String>>()
    private var revision = 0L
    @Volatile var snapshot: Map<String, Any?> = mapOf("revision" to 0, "conversations" to emptyList<Any>())
        private set
    private var routeRevision = 0L
    private var routes: Map<String, Any?> = mapOf("revision" to 0, "routes" to emptyList<Any>(), "current" to null)
    val actions = ActionCoordinator({ name, body -> emit(name, body) }, ::publish).apply {
        acceptUnhandled = metadata?.getString("ExpoCallKitTelecomUnhandledAction") == "accept"
    }
    private val audio = context.getSystemService(AudioManager::class.java)

    fun audioRoutes() = routes
    private fun publish() {
        snapshot = mapOf("revision" to ++revision, "conversations" to calls.map { (id, call) ->
            buildMap<String, Any?> {
                putAll(call.state.filterKeys { it != "endReason" }.filterValues { it != null })
                actions.pending(id)?.let { put("pending", it) }
            } }.sortedBy { it["id"] as String })
        emit("conversationsChanged", snapshot)
    }
    private fun call(id: String) = calls[id] ?: error("unknown_conversation")
    private suspend fun control(id: String) = call(id).ready.await()
    private fun checked(result: CallControlResult) {
        if (result is CallControlResult.Error) throw ConversationFailure("conversation", result.errorCode)
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
        "error" to mapOf("code" to code, "message" to "Incoming report failed", "nativeCode" to nativeCode).filterValues { it != null })

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
            "isMuted" to false, "isVideo" to video, "payload" to payload?.filterKeys { it != "media" },
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
                    onAnswer = { withTimeout(4500) {
                        if (call.state["status"] == "ringing") { action(id, "join", "system"); call.active = true; joined(id) }
                    } },
                    onDisconnect = { withTimeout(4500) { action(id, "end", "system"); terminal(id, "local") } },
                    onSetActive = { withTimeout(4500) { hold(id, false, "system") } },
                    onSetInactive = { withTimeout(4500) { hold(id, true, "system") } }) {
                    call.ready.complete(this)
                    ConversationService.show(context, id, call.state)
                    call.timeout = jobs.launch {
                        delay((metadata?.getInt(if (incoming) "ExpoCallKitTelecomIncomingCallTimeout" else "ExpoCallKitTelecomOutgoingCallTimeout",
                            if (incoming) 45 else 60) ?: if (incoming) 45 else 60).coerceAtLeast(1).toLong() * 1000)
                        if (calls[id] === call && call.state["connectedAt"] == null) terminateLater(id, "unanswered")
                    }
                    val controls = this
                    call.collectors = jobs.launch {
                        try { coroutineScope {
                    launch { controls.availableEndpoints.collect { call.endpoints = it; updateRoutes(id, null) } }
                    launch { controls.currentCallEndpoint.collect { updateRoutes(id, it) } }
                    launch { controls.isMuted.collect { muted ->
                        if (calls[id] === call && call.state["isMuted"] != muted) mute(id, muted, "system")
                    } }
                        } } catch (error: Exception) {
                            if (error !is CancellationException && calls[id] === call) terminateLater(id, "failed")
                        }
                    }
                }
            } catch (e: Exception) { call.ready.completeExceptionally(e) }
            finally {
                call.collectors?.cancel()
                if (calls[id] === call) terminal(id, call.state["endReason"] as? String ?: "failed")
            }
        }
        try { withTimeout(6000) { call.ready.await() } }
        catch (error: Exception) { call.job?.cancel(); throw error }
        if (incoming && policy.optString("type", "native") == "native") {
            call.media = NativeMediaController(context, payload!!, policy, jobs, failed = {
                terminateLater(id, "failed")
            }, connected = {
                if (calls[id] === call) {
                    call.timeout?.cancel()
                    call.state["connectedAt"] = call.state["connectedAt"] ?: Instant.now().toString()
                    if (call.state["status"] != "held") call.state["status"] = "connected"
                    publish()
                }
            }).also { it.prewarm() }
        }
    }

    private suspend fun action(id: String, kind: String, origin: String, args: Map<String, Any?> = emptyMap()) {
        val current = call(id)
        actions.coldJoinBudgetMs = policy.optLong("coldLaunchJoinBudgetMs", 3500).coerceIn(1, 3500)
        val nativeIncoming = policy.optString("type", "native") == "native" && current.state["direction"] == "incoming"
        if (!nativeIncoming || kind != "join")
            actions.perform(current.state.toMap(), kind, origin, args)
        check(calls[id] === current) { "unknown_conversation" }
    }
    suspend fun join(id: String) {
        require(call(id).state["status"] == "ringing") { "invalid_arguments" }
        try { action(id, "join", "app") }
        catch (error: Exception) { if (calls.containsKey(id)) finish(id, "failed"); throw error }
        checked(control(id).answer(if (call(id).state["isVideo"] == true) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL))
        call(id).active = true
        joined(id)
    }
    private fun joined(id: String) {
        val current = call(id)
        current.state["status"] = "connecting"
        ConversationService.show(context, id, current.state)
        current.media?.answered { awaitAudio(id, current) }
        publish()
    }
    private suspend fun awaitAudio(id: String, current: Call) {
        // Returning through main lets Core-Telecom finish a system callback before audio begins.
        withContext(Dispatchers.Main) { yield(); check(calls[id] === current && current.active) }
        withTimeout(1500) {
            while (audio.mode != AudioManager.MODE_IN_COMMUNICATION || routes["current"] == null) delay(10)
            check(calls[id] === current && current.active)
        }
    }
    suspend fun end(id: String) {
        try { action(id, "end", "app") }
        catch (error: Exception) { if (calls.containsKey(id)) finish(id, "failed"); throw error }
        finish(id, "local")
    }
    suspend fun hold(id: String, held: Boolean, origin: String = "app") {
        val current = call(id)
        if ((current.state["status"] == "held") == held) return
        action(id, "hold", origin, mapOf("held" to held))
        if (origin == "app") checked(if (held) control(id).setInactive() else control(id).setActive())
        current.active = !held
        if (held || current.state["isMuted"] == true) current.media?.mute(true)
        else current.media?.unmute { awaitAudio(id, current) }
        current.state["status"] = if (held) "held" else if (current.state["connectedAt"] != null) "connected" else "connecting"
        publish()
    }
    suspend fun mute(id: String, muted: Boolean, origin: String = "app") {
        val current = call(id)
        if (current.state["isMuted"] == muted) return
        action(id, "mute", origin, mapOf("muted" to muted))
        if (muted) current.media?.mute(true) else current.media?.unmute { awaitAudio(id, current) }
        if (current.active && origin == "app") audio.isMicrophoneMute = muted
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
                require(current.state["status"] != "ringing") { "invalid_arguments" }
                if (current.state["status"] != "held") { if (!current.active) checked(control(id).setActive()); current.active = true; current.state["status"] = "connected" }
                current.timeout?.cancel()
                current.state["connectedAt"] = current.state["connectedAt"] ?: Instant.now().toString()
            }
            "updated" -> { report["members"]?.let { current.state["members"] = it }; report["video"]?.let { current.state["isVideo"] = it } }
            "ended" -> { finish(id, report["reason"] as? String ?: error("invalid_arguments")); return }
            else -> error("invalid_arguments")
        }
        publish()
    }
    private fun terminateLater(id: String, reason: String) = jobs.launch {
        try { if (calls.containsKey(id)) finish(id, reason) }
        catch (_: Exception) { terminal(id, "failed") }
    }
    private suspend fun finish(id: String, reason: String) {
        require(reason in setOf("local", "remoteEnded", "failed", "unanswered", "answeredElsewhere", "declinedElsewhere")) { "invalid_arguments" }
        val cause = when (reason) { "local", "failed" -> DisconnectCause.LOCAL; "unanswered" -> DisconnectCause.MISSED; else -> DisconnectCause.REMOTE }
        call(id).state["endReason"] = reason
        checked(control(id).disconnect(DisconnectCause(cause)))
        if (calls.containsKey(id)) terminal(id, reason)
    }
    private fun terminal(id: String, reason: String) {
        val current = calls[id] ?: return
        current.timeout?.cancel()
        current.collectors?.cancel()
        current.state["status"] = "ended"
        current.state["ended"] = mapOf("at" to Instant.now().toString(), "reason" to reason)
        current.media?.close(if (reason == "local" && current.state["connectedAt"] == null) "declined" else "ended")
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
