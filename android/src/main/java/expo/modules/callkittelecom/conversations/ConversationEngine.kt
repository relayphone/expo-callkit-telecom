package expo.modules.callkittelecom.conversations

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationManager
import androidx.core.content.ContextCompat
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
    private var startingEndpoints: List<CallEndpointCompat> = emptyList()
    init {
        // Keep this collection alive through addCall: Core-Telecom shares Bluetooth
        // endpoint UUIDs between the starting-flow session and the admitted call.
        // Call admission only reads the cache and never waits for endpoint discovery.
        jobs.launch {
            try {
                telecom.getAvailableStartingCallEndpoints().collect { startingEndpoints = it.toList() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Discovery is optional; Core-Telecom still selects its default route.
            } finally {
                startingEndpoints = emptyList()
            }
        }
    }
    private val metadata = context.packageManager.getApplicationInfo(context.packageName,
        android.content.pm.PackageManager.GET_META_DATA).metaData
    var policy = org.json.JSONObject(metadata?.getString("ExpoCallKitTelecomJoinPolicy") ?: "{}")
    private class Call(val state: MutableMap<String, Any?>) {
        val ready = CompletableDeferred<CallControlScope>()
        val permitted = CompletableDeferred<Boolean>()
        var job: Job? = null
        var collectors: Job? = null
        var active = false
        var admitted = false
        var visible = false
        var endpoint: CallEndpointCompat? = null
        var media: NativeMediaController? = null
        var timeout: Job? = null
        var endpoints: List<CallEndpointCompat> = emptyList()
    }
    private val calls = linkedMapOf<String, Call>()
    private val recent = mutableMapOf<String, Pair<Long, Map<String, Any?>>>()
    private val incomingTasks = mutableMapOf<String, CompletableDeferred<Map<String, Any?>>>()
    private val incomingPolicy = IncomingPolicy(context)
    private var policyDirty = false
    private var policyCheck: Job? = null
    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { recheckIncomingPolicy() }
        }, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED).apply {
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    internal var incomingTimeoutMillis = (metadata?.getInt("ExpoCallKitTelecomIncomingCallTimeout", 45) ?: 45).coerceAtLeast(1).toLong()*1000
    internal var answerTimeoutMillis = (metadata?.getInt("ExpoCallKitTelecomFulfillAnswerCallTimeout", 30) ?: 30).coerceAtLeast(1).toLong() * 1000
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
        val states = calls.filterValues { it.admitted && it.visible }.map { (id, call) ->
            buildMap<String, Any?> {
                putAll(call.state.filterKeys { it != "endReason" }.filterValues { it != null })
                actions.pending(id)?.let { put("pending", it) }
            }
        }.sortedBy { it["id"] as String }
        if (snapshot["conversations"] == states) return
        snapshot = mapOf("revision" to ++revision, "conversations" to states)
        emit("conversationsChanged", snapshot)
    }
    @Suppress("UNCHECKED_CAST")
    private fun signal(current: Call, stage: NativeMediaStage, measurements: Map<String, Long> = emptyMap()) {
        ((current.state["payload"] as? Map<String, Any?>)?.get("serverCallId") as? String)
            ?.let { NativeMediaSignals.emit(it, stage, measurements) }
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
        if (duplicate != null) return duplicate.second
        (incomingTasks["event:$eventId"] ?: incomingTasks["call:$serverId"])?.let { return it.await() }
        val id = CallIdentity.forServerCallId(serverId).toString()
        if (calls.containsKey(id)) { control(id); return mapOf("status" to "reported", "id" to id) }
        val pending = CompletableDeferred<Map<String, Any?>>()
        incomingTasks["event:$eventId"] = pending; incomingTasks["call:$serverId"] = pending
        val outcome = try {
            add(id, caller, payload["hasVideo"] == true, payload)
            val appFilter = incomingPolicy.custom(payload)
            val filter = appFilter ?: incomingPolicy.system(payload)
            call(id).permitted.complete(filter == null)
            // A DND-suppressed notification still records repeat-caller history when cancelled.
            // App block-list/other rejections are never presented.
            if (appFilter == null) ConversationService.show(context, id, call(id).state)
            if (filter != null && call(id).state["status"] == "ringing") {
                closeFiltered(id)
                filtered(filter)
            } else {
                call(id).visible = true
                publish()
                prepareMedia(id, payload)
                mapOf("status" to "reported", "id" to id)
            }
        } catch (e: CallException) {
            if (e.code == CallException.ERROR_CALL_NOT_PERMITTED_AT_PRESENT_TIME) mapOf("status" to "atCapacity")
            else failure("conversation", e.code.toString())
        } catch (e: Exception) {
            withContext(NonCancellable) { if (calls.containsKey(id)) runCatching { finish(id, "failed") } }
            failure("conversation")
        }
        if (outcome["status"] in setOf("reported", "filtered")) {
            recent["event:$eventId"] = now to outcome; recent["call:$serverId"] = now to outcome
        }
        pending.complete(outcome)
        incomingTasks.remove("event:$eventId"); incomingTasks.remove("call:$serverId")
        deliverIncomingOutcome(payload, outcome)
        return outcome
    }
    private fun filtered(filter: NativeIncomingFilter): Map<String, Any?> =
        mapOf("status" to "filtered", "reason" to filter.reason.value, "nativeReason" to filter.nativeReason).filterValues { it != null }
    private fun deliverIncomingOutcome(payload: Map<String, Any?>, outcome: Map<String, Any?>) {
        NativeIncomingOutcomes.emit(payload, outcome)
        emit("conversationEvent", mapOf("type" to "incomingConversationOutcome", "payload" to mapOf(
            "eventId" to payload["eventId"], "serverCallId" to payload["serverCallId"], "outcome" to outcome)))
    }
    private suspend fun closeFiltered(id: String) {
        call(id).media?.close("filtered")
        finish(id, "local")
    }
    /** Also closes an already-prewarmed ringing call if the user's policy changes. */
    fun recheckIncomingPolicy() {
        policyDirty = true
        if (policyCheck?.isActive == true) return
        policyCheck = jobs.launch {
            while (policyDirty) {
                policyDirty = false
                for ((id, current) in calls.toMap()) {
                    if (!current.visible || current.state["status"] != "ringing") continue
                    @Suppress("UNCHECKED_CAST") val payload = current.state["payload"] as? Map<String, Any?> ?: continue
                    val filter = runCatching { incomingPolicy.evaluate(payload) }.getOrNull() ?: continue
                    if (calls[id] !== current || current.state["status"] != "ringing") continue
                    closeFiltered(id)
                    val outcome = filtered(filter)
                    val now = android.os.SystemClock.elapsedRealtime()
                    recent["event:${payload["eventId"]}"] = now to outcome
                    recent["call:${payload["serverCallId"]}"] = now to outcome
                    deliverIncomingOutcome(payload, outcome)
                }
            }
        }
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
        call.visible = !incoming
        if (!incoming) call.permitted.complete(true)
        calls[id] = call
        publish()
        val handle = (member["phoneNumber"] ?: member["email"] ?: member["id"]).toString()
        val preferredEndpoint = preferredStartingEndpoint(video)
        signal(call, NativeMediaStage.ROUTE_PREFERENCE_REQUESTED, mapOf(
            "cached" to if (preferredEndpoint != null) 1L else 0L,
            "type" to (preferredEndpoint?.type?.toLong() ?: 0L),
            "availableCount" to startingEndpoints.size.toLong()))
        val attributes = CallAttributesCompat((member["displayName"] ?: handle).toString(), Uri.fromParts("sip", handle, null),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE, preferredStartingCallEndpoint = preferredEndpoint)
        call.job = jobs.launch {
            try {
                signal(call, NativeMediaStage.CORE_ADD_REQUESTED)
                telecom.addCall(attributes,
                    onAnswer = { withTimeout(4500) {
                        check(call.permitted.await()) { "Incoming call filtered" }
                        if (call.state["status"] == "ringing") { signal(call, NativeMediaStage.ANSWER_REQUESTED); call.timeout?.cancel(); action(id, "join", "system"); call.active = true; joined(id) }
                    } },
                    onDisconnect = { withTimeout(4500) { cleanupDecline(id); action(id, "end", "system"); terminal(id, "local") } },
                    onSetActive = { withTimeout(4500) { check(call.permitted.await()); hold(id, false, "system") } },
                    onSetInactive = { withTimeout(4500) { hold(id, true, "system") } }) {
                    call.admitted = true
                    signal(call, NativeMediaStage.CORE_ADMITTED)
                    signal(call, NativeMediaStage.REPORTED)
                    publish()
                    call.ready.complete(this)
                    if (!incoming) ConversationService.show(context, id, call.state)
                    call.timeout = jobs.launch {
                        delay(if (incoming) incomingTimeoutMillis else
                            (metadata?.getInt("ExpoCallKitTelecomOutgoingCallTimeout", 60) ?: 60).coerceAtLeast(1).toLong()*1000)
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
    }
    private fun prepareMedia(id: String, payload: Map<String, Any?>) {
        val call = call(id)
        if (policy.optString("type", "native") == "native") {
            call.media = NativeMediaController(context, payload, policy, jobs, failed = {
                terminateLater(id, "failed")
            }, connected = {
                if (calls[id] === call) {
                    call.timeout?.cancel()
                    call.state["connectedAt"] = call.state["connectedAt"] ?: Instant.now().toString()
                    if (call.state["status"] != "held") call.state["status"] = "connected"
                    publish()
                }
            }).also { it.prewarm() }
            if (call.active) joined(id)
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
        check(call(id).permitted.await()) { "Incoming call filtered" }
        signal(call(id), NativeMediaStage.ANSWER_REQUESTED)
        call(id).timeout?.cancel()
        try { action(id, "join", "app") }
        catch (error: Exception) { if (calls.containsKey(id)) finish(id, "failed"); throw error }
        checked(control(id).answer(if (call(id).state["isVideo"] == true) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL))
        call(id).active = true
        joined(id)
    }
    private fun joined(id: String) {
        val current = call(id)
        signal(current, NativeMediaStage.ANSWER_ACCEPTED)
        current.state["status"] = "connecting"
        current.timeout?.cancel()
        current.timeout = jobs.launch {
            delay(answerTimeoutMillis)
            if (calls[id] === current && current.state["connectedAt"] == null) terminateLater(id, "failed")
        }
        ConversationService.show(context, id, current.state)
        current.media?.answered { awaitAudio(id, current) }
        refreshRoutes()
        publish()
    }
    private suspend fun awaitAudio(id: String, current: Call) {
        // Returning through main lets Core-Telecom finish a system callback before audio begins.
        withContext(Dispatchers.Main) { yield(); check(calls[id] === current && current.active) }
        signal(current, NativeMediaStage.AUDIO_WAIT_STARTED)
        try { withTimeout(1500) {
            var modeObserved = false
            while (audio.mode != AudioManager.MODE_IN_COMMUNICATION || current.endpoint == null) {
                if (!modeObserved && audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    signal(current, NativeMediaStage.AUDIO_MODE_READY); modeObserved = true
                }
                delay(10)
            }
            if (!modeObserved) signal(current, NativeMediaStage.AUDIO_MODE_READY)
            check(calls[id] === current && current.active)
            signal(current, NativeMediaStage.AUDIO_AUTHORIZED)
        } } catch (error: TimeoutCancellationException) {
            @Suppress("UNCHECKED_CAST") val serverId = (current.state["payload"] as? Map<String, Any?>)?.get("serverCallId") as? String
            serverId?.let { NativeMediaSignals.emit(it, NativeMediaStage.AUDIO_WAIT_EXPIRED, mapOf(
                "audioMode" to audio.mode.toLong(), "hasEndpoint" to if (current.endpoint != null) 1L else 0L,
                "active" to if (current.active) 1L else 0L)) }
            throw error
        }
    }
    private fun cleanupDecline(id: String) {
        val current = call(id)
        if (current.state["status"] == "ringing") current.media?.close("declined")
    }
    suspend fun end(id: String) {
        cleanupDecline(id)
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
        refreshRoutes()
        current.state["status"] = if (held) "held" else if (current.state["connectedAt"] != null) "connected" else "connecting"
        // Publish native authority before media suspension can yield to transport reports.
        publish()
        if (held) current.media?.pause() else current.media?.resume { awaitAudio(id, current) }
    }
    suspend fun mute(id: String, muted: Boolean, origin: String = "app") {
        val current = call(id)
        if (current.state["isMuted"] == muted) return
        action(id, "mute", origin, mapOf("muted" to muted))
        if (muted) current.media?.mute()
        else if (current.active) current.media?.unmute { awaitAudio(id, current) }
        else current.media?.clearMute()
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
        current.permitted.complete(false)
        current.timeout?.cancel()
        current.collectors?.cancel()
        current.state["status"] = "ended"
        current.state["ended"] = mapOf("at" to Instant.now().toString(), "reason" to reason)
        current.media?.close(if (reason == "local" && current.state["connectedAt"] == null) "declined" else "ended")
        actions.cancel(id)
        publish()
        calls.remove(id)
        refreshRoutes()
        ConversationService.remove(context, id)
        publish()
    }
    private fun preferredStartingEndpoint(video: Boolean): CallEndpointCompat? {
        // Only pass endpoints emitted by the public starting-endpoint flow. Preserve
        // an existing active route, including a speaker the user already selected.
        val current = calls.values.firstOrNull { it.active }?.endpoint
        startingEndpoints.firstOrNull { it.identifier == current?.identifier }?.let { return it }
        startingEndpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }?.let { return it }
        val bluetooth = startingEndpoints.filter { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
        if (bluetooth.isNotEmpty()) return bluetooth.singleOrNull()
        // Preserve Core's video default. Audio calls prefer private audio; a sole
        // speaker endpoint can be preselected without choosing it over a private route.
        if (video) return null
        startingEndpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }?.let { return it }
        return startingEndpoints.singleOrNull()?.takeIf { it.type == CallEndpointCompat.TYPE_SPEAKER }
    }
    private fun route(endpoint: CallEndpointCompat) = mapOf("id" to endpoint.identifier.toString(), "name" to endpoint.name.toString(),
        "type" to when(endpoint.type) { CallEndpointCompat.TYPE_EARPIECE -> "builtInReceiver"; CallEndpointCompat.TYPE_SPEAKER -> "builtInSpeaker";
            CallEndpointCompat.TYPE_BLUETOOTH -> "bluetoothHFP"; CallEndpointCompat.TYPE_WIRED_HEADSET -> "headphones"; else -> "unknown" })
    private fun updateRoutes(id: String, endpoint: CallEndpointCompat?) {
        val current = calls[id] ?: return
        if (endpoint != null) {
            current.endpoint = endpoint
            @Suppress("UNCHECKED_CAST") val serverId = (current.state["payload"] as? Map<String, Any?>)?.get("serverCallId") as? String
            serverId?.let { NativeMediaSignals.emit(it, NativeMediaStage.ENDPOINT_READY, mapOf("type" to endpoint.type.toLong())) }
        }
        refreshRoutes()
    }
    private fun refreshRoutes() {
        val selected = calls.values.firstOrNull { it.active } ?: calls.values.firstOrNull { it.admitted }
        val available = selected?.endpoints?.map(::route) ?: emptyList()
        val current = selected?.endpoint?.let(::route)
        if (routes["routes"] == available && routes["current"] == current) return
        routes = mapOf("revision" to ++routeRevision, "routes" to available, "current" to current)
        emit("audioRoutesChanged", routes)
    }
    suspend fun selectRoute(id: String) {
        val current = calls.values.firstOrNull { it.active } ?: error("unknown_conversation")
        val endpoint = current.endpoints.firstOrNull { it.identifier.toString() == id } ?: error("invalid_arguments")
        checked(current.ready.await().requestEndpointChange(endpoint))
    }
}
