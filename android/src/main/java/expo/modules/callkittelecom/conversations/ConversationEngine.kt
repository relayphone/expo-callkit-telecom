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
    fun receiveCallEnded(payload: Map<String, Any?>): Boolean =
        NativeCallEnded.typed(payload)?.let(::receiveEnded) ?: false
    fun remoteEnd(payload: Map<String, Any?>) { NativeCallEnded.wire(payload)?.let(::receiveEnded) }
    private fun receiveEnded(payload: NativeCallEnded): Boolean {
        val entry = endedIntake.accept(payload) ?: return false
        calls[entry.id]?.takeIf { it.incoming === entry && !it.terminal }?.let { current ->
            if (current.terminalIntent == null) current.state["endReason"] = payload.reason
            current.permitted.complete(false)
            current.timeout?.cancel()
            current.media?.close("ended")
            // The intent is already claimed before cancellation can reenter a handler.
            actions.cancel(entry.id)
            drainEnded(current)
        }
        return true
    }
    private fun drainEnded(current: Call) {
        if (current.endJob != null || current.terminal || current.incoming?.intent == null) return
        val id = current.state["id"] as String
        val work = jobs.launch(start = CoroutineStart.LAZY) {
            try { if (calls[id] === current) finish(id, current.incoming!!.intent!!.payload.reason) }
            catch (_: Exception) { if (calls[id] === current) terminal(id, "failed") }
        }
        current.endJob = work
        work.start()
    }
    internal fun canWarmIncoming(id: String): Boolean = calls[id]?.let { !it.terminal && it.incoming?.intent == null } == true
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
        var incoming: NativeEndedIntake.Entry? = null
        var endJob: Job? = null
        var ending = false
        var terminal = false
        var terminalIntent: NativeEndedIntake.Intent? = null
    }
    private val calls = linkedMapOf<String, Call>()
    private val recent = mutableMapOf<String, Pair<Long, Map<String, Any?>>>()
    private val incomingTasks = mutableMapOf<String, CompletableDeferred<Map<String, Any?>>>()
    private val endedIntake = NativeEndedIntake()
    private var publishing = false
    private var flushingTerminals = false
    private val pendingTerminals = linkedMapOf<String, Call>()
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
    private var publishedRevision = 0L
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
        if (snapshot["conversations"] != states) snapshot = mapOf("revision" to ++revision, "conversations" to states)
        if (publishing) return
        publishing = true
        try {
            var update = snapshot
            if (publishedRevision != (update["revision"] as Number).toLong()) while (true) {
                publishedRevision = (update["revision"] as Number).toLong()
                emit("conversationsChanged", update)
                if (update["revision"] == snapshot["revision"]) break
                update = snapshot
            }
        } finally { publishing = false }
        flushTerminals()
    }
    private fun flushTerminals() {
        if (publishing || flushingTerminals) return
        flushingTerminals = true
        try { while (pendingTerminals.isNotEmpty()) {
            val batch = pendingTerminals.toMap()
            for ((id, current) in batch) {
                pendingTerminals.remove(id)
                val intent = current.terminalIntent
                if (current.state["ended"] is Map<*, *> &&
                    (current.state["ended"] as Map<*, *>)["reason"] == "answeredElsewhere" &&
                    current.admitted && intent?.payload?.reason == "answeredElsewhere" && !intent.emitted) {
                    intent.emitted = true
                    emit("conversationEvent", mapOf("type" to "answeredElsewhere", "payload" to mapOf(
                        "conversationId" to id, "serverCallId" to intent.payload.serverCallId, "eventId" to intent.payload.eventId)))
                }
                if (calls[id] !== current) continue
                calls.remove(id)
                current.incoming?.let(endedIntake::removed)
                refreshRoutes()
                ConversationService.remove(context, id)
            }
            publish()
        } } finally { flushingTerminals = false }
    }
    @Suppress("UNCHECKED_CAST")
    private fun signal(current: Call, stage: NativeMediaStage, measurements: Map<String, Long> = emptyMap()) {
        ((current.state["payload"] as? Map<String, Any?>)?.get("serverCallId") as? String)
            ?.let { NativeMediaSignals.emit(it, stage, measurements) }
    }
    private fun call(id: String) = calls[id] ?: error("unknown_conversation")
    private fun live(current: Call): Boolean = calls[current.state["id"] as String] === current &&
        !current.terminal && !current.ending && current.incoming?.intent == null
    private fun requireLive(current: Call) { check(live(current)) { "unknown_conversation" } }
    private suspend fun control(id: String): CallControlScope {
        val current = call(id)
        return current.ready.await().also { requireLive(current) }
    }
    private fun checked(result: CallControlResult) {
        if (result is CallControlResult.Error) throw ConversationFailure("conversation", result.errorCode)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun incoming(input: Map<String, Any?>): Map<String, Any?> {
        val eventId = input["eventId"] as? String ?: return failure("invalid_arguments")
        val serverId = input["serverCallId"] as? String ?: return failure("invalid_arguments")
        val caller = (input["caller"] as? Map<*, *>)?.let(NativeCaller::parse) ?: return failure("invalid_arguments")
        if (!NativeCallEnded.validIdentifier(eventId) || !NativeCallEnded.validIdentifier(serverId)) return failure("invalid_arguments")
        val payload = input + ("caller" to caller.asPayload())
        val now = android.os.SystemClock.elapsedRealtime()
        recent.entries.removeAll { now - it.value.first > 120_000 }
        val duplicate = recent["event:$eventId"] ?: recent["call:$serverId"]
        if (duplicate != null) return duplicate.second
        (incomingTasks["event:$eventId"] ?: incomingTasks["call:$serverId"])?.let { return it.await() }
        val id = CallIdentity.forServerCallId(serverId).toString()
        if (calls.containsKey(id)) { control(id); return mapOf("status" to "reported", "id" to id) }
        val pending = CompletableDeferred<Map<String, Any?>>()
        incomingTasks["event:$eventId"] = pending; incomingTasks["call:$serverId"] = pending
        val ownership = endedIntake.reserve(serverId, id)
        val outcome = try {
            val current = add(id, caller.asParticipant(), payload["hasVideo"] == true, payload, caller.coreAddress(), ownership)
            val appFilter = if (ownership.intent == null) incomingPolicy.custom(payload) else null
            val filter = appFilter ?: incomingPolicy.system(payload)
            if (ownership.intent != null) {
                current.permitted.complete(false)
                drainEnded(current)
                mapOf("status" to "reported", "id" to id)
            } else if (calls[id] !== current || current.terminal) failure("conversation") else {
                current.permitted.complete(filter == null)
                // A DND-suppressed notification still records repeat-caller history when cancelled.
                // App block-list/other rejections are never presented.
                if (appFilter == null) ConversationService.show(context, id, current.state)
                if (filter != null && current.state["status"] == "ringing") {
                    closeFiltered(id)
                    filtered(filter)
                } else {
                    current.visible = true
                    publish()
                    if (calls[id] === current && ownership.intent == null) prepareMedia(id, payload)
                    mapOf("status" to "reported", "id" to id)
                }
            }
        } catch (e: CallException) {
            if (e.code == CallException.ERROR_CALL_NOT_PERMITTED_AT_PRESENT_TIME) mapOf("status" to "atCapacity")
            else failure("conversation", e.code.toString())
        } catch (e: Exception) {
            withContext(NonCancellable) { if (calls.containsKey(id)) runCatching { finish(id, "failed") } }
            failure("conversation")
        }
        endedIntake.settled(ownership)
        if (calls[id]?.incoming !== ownership) endedIntake.removed(ownership)
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
    private suspend fun add(id: String, member: Map<String, Any?>, video: Boolean, payload: Map<String, Any?>?, address: Uri? = null,
                            incomingOwnership: NativeEndedIntake.Entry? = null): Call {
        val incoming = payload != null
        val call = Call(linkedMapOf("id" to id, "direction" to if (incoming) "incoming" else "outgoing",
            "status" to if (incoming) "ringing" else "dialing", "members" to listOf(member),
            "isMuted" to false, "isVideo" to video, "payload" to payload?.filterKeys { it != "media" },
            "capabilities" to mapOf("hold" to true, "merge" to false, "tones" to true, "video" to true)))
        call.visible = !incoming
        call.incoming = incomingOwnership
        if (!incoming) call.permitted.complete(true)
        calls[id] = call
        publish()
        val handle = (member["phoneNumber"] ?: member["email"] ?: member["id"]).toString()
        val preferredEndpoint = preferredStartingEndpoint(video)
        signal(call, NativeMediaStage.ROUTE_PREFERENCE_REQUESTED, mapOf(
            "cached" to if (preferredEndpoint != null) 1L else 0L,
            "type" to (preferredEndpoint?.type?.toLong() ?: 0L),
            "availableCount" to startingEndpoints.size.toLong()))
        val attributes = CallAttributesCompat((member["displayName"] ?: handle).toString(), address ?: Uri.fromParts("sip", handle, null),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE, preferredStartingCallEndpoint = preferredEndpoint)
        call.job = jobs.launch {
            try {
                signal(call, NativeMediaStage.CORE_ADD_REQUESTED)
                telecom.addCall(attributes,
                    onAnswer = { withTimeout(4500) {
                        check(call.permitted.await()) { "Incoming call filtered" }
                        if (call.state["status"] == "ringing") { signal(call, NativeMediaStage.ANSWER_REQUESTED); call.timeout?.cancel(); action(id, "join", "system"); requireLive(call); call.active = true; joined(id) }
                    } },
                    onDisconnect = { withTimeout(4500) { cleanupDecline(id); action(id, "end", "system"); terminal(id, "local") } },
                    onSetActive = { withTimeout(4500) { check(call.permitted.await()); hold(id, false, "system") } },
                    onSetInactive = { withTimeout(4500) { hold(id, true, "system") } }) {
                    call.admitted = true
                    call.incoming?.let(endedIntake::admitted)
                    signal(call, NativeMediaStage.CORE_ADMITTED)
                    signal(call, NativeMediaStage.REPORTED)
                    publish()
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
                    // Install ownership/cleanup before waking incoming or retained end work.
                    call.ready.complete(this)
                }
            } catch (e: Exception) { call.ready.completeExceptionally(e) }
            finally {
                call.collectors?.cancel()
                if (calls[id] === call) terminal(id, call.state["endReason"] as? String ?: "failed")
            }
        }
        try { withTimeout(6000) { call.ready.await() } }
        catch (error: Exception) { call.job?.cancel(); throw error }
        return call
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
        requireLive(current)
        actions.coldJoinBudgetMs = policy.optLong("coldLaunchJoinBudgetMs", 3500).coerceIn(1, 3500)
        val nativeIncoming = policy.optString("type", "native") == "native" && current.state["direction"] == "incoming"
        if (!nativeIncoming || kind != "join")
            actions.perform(current.state.toMap(), kind, origin, args)
        requireLive(current)
    }
    suspend fun join(id: String) {
        val current = call(id)
        require(current.state["status"] == "ringing") { "invalid_arguments" }
        check(current.permitted.await()) { "Incoming call filtered" }
        requireLive(current)
        signal(current, NativeMediaStage.ANSWER_REQUESTED)
        current.timeout?.cancel()
        try { action(id, "join", "app") }
        catch (error: Exception) { if (calls.containsKey(id)) finish(id, "failed"); throw error }
        checked(control(id).answer(if (current.state["isVideo"] == true) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL))
        requireLive(current)
        current.active = true
        joined(id)
    }
    private fun joined(id: String) {
        val current = call(id)
        requireLive(current)
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
    private suspend fun awaitAudio(id: String, current: Call): Unit = withContext(Dispatchers.Main) {
        // Returning through main lets Core-Telecom finish a system callback before audio begins.
        yield(); check(live(current) && current.active)
        signal(current, NativeMediaStage.AUDIO_WAIT_STARTED)
        try {
            // Telecom's successful answer/setActive transaction already verifies this
            // call owns focus. Endpoint callbacks describe route/UI state and may be absent;
            // they are not another audio activation acknowledgment. Mode changes are async.
            NativeAudioAuthority.await({ live(current) && current.active }, { audio.mode })
            signal(current, NativeMediaStage.AUDIO_MODE_READY)
            check(live(current) && current.active)
            signal(current, NativeMediaStage.AUDIO_AUTHORIZED, mapOf(
                "audioMode" to AudioManager.MODE_IN_COMMUNICATION.toLong(), "coreActive" to 1L,
                "endpointObserved" to if (current.endpoint != null) 1L else 0L))
        } catch (error: TimeoutCancellationException) {
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
        requireLive(current)
        current.active = !held
        refreshRoutes()
        requireLive(current)
        current.state["status"] = if (held) "held" else if (current.state["connectedAt"] != null) "connected" else "connecting"
        // Publish native authority before media suspension can yield to transport reports.
        publish()
        requireLive(current)
        if (held) current.media?.pause() else current.media?.resume { awaitAudio(id, current) }
    }
    suspend fun mute(id: String, muted: Boolean, origin: String = "app") {
        val current = call(id)
        if (current.state["isMuted"] == muted) return
        action(id, "mute", origin, mapOf("muted" to muted))
        if (muted) current.media?.mute()
        else if (current.active) current.media?.unmute { awaitAudio(id, current) }
        else current.media?.clearMute()
        requireLive(current)
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
        if (report["type"] != "ended") requireLive(current)
        when (report["type"]) {
            "connecting" -> if (current.state["status"] in listOf("dialing", "connecting")) current.state["status"] = "connecting"
            "connected" -> {
                require(current.state["status"] != "ringing") { "invalid_arguments" }
                if (current.state["status"] != "held") { if (!current.active) checked(control(id).setActive()); requireLive(current); current.active = true; current.state["status"] = "connected" }
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
        val current = call(id)
        if (current.ending || current.terminal) return
        current.ending = true
        try {
            val controls = current.ready.await()
            if (calls[id] !== current || current.terminal) return
            // Claim the actual native request before its callback or SDK publication can reenter.
            val intent = current.incoming?.intent
            current.terminalIntent = intent
            val endedReason = intent?.payload?.reason ?: reason
            val cause = intent?.payload?.disconnectCause() ?: DisconnectCause(when (reason) {
                "local", "failed" -> DisconnectCause.LOCAL
                "unanswered" -> DisconnectCause.MISSED
                "answeredElsewhere" -> if (android.os.Build.VERSION.SDK_INT >= 34) DisconnectCause.REMOTE else DisconnectCause.ANSWERED_ELSEWHERE
                else -> DisconnectCause.REMOTE
            }, reason)
            current.state["endReason"] = endedReason
            checked(controls.disconnect(cause))
            if (calls[id] === current) terminal(id, endedReason)
        } catch (error: Exception) { current.ending = false; throw error }
    }
    private fun terminal(id: String, reason: String) {
        val current = calls[id] ?: return
        if (current.terminal) return
        current.terminal = true
        current.permitted.complete(false)
        current.timeout?.cancel()
        current.collectors?.cancel()
        current.state["status"] = "ended"
        current.state["ended"] = mapOf("at" to Instant.now().toString(), "reason" to reason)
        current.visible = true
        pendingTerminals[id] = current
        current.media?.close(if (reason == "local" && current.state["connectedAt"] == null) "declined" else "ended")
        actions.cancel(id)
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
        val controls = current.ready.await()
        requireLive(current)
        checked(controls.requestEndpointChange(endpoint))
        requireLive(current)
    }
}
