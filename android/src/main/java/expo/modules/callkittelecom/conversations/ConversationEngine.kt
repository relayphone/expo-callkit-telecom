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

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach

/** All mutable state and Core-Telecom controls are confined to Dispatchers.Main. */
class ConversationEngine(val context: Context) {
    internal var controlObserver: NativeControlObserver? = null
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
    fun remoteEnd(payload: Map<String, Any?>) { remoteEnd(payload) {} }
    internal fun remoteEnd(payload: Map<String, Any?>, onConsumed: () -> Unit) {
        NativeCallEnded.wire(payload)?.let { receiveEnded(it, onConsumed) }
    }
    private fun receiveEnded(payload: NativeCallEnded): Boolean = receiveEnded(payload) {}
    private fun receiveEnded(payload: NativeCallEnded, onConsumed: () -> Unit): Boolean {
        val entry = endedIntake.accept(payload, onConsumed) ?: return false
        calls[entry.id]?.takeIf { it.incoming === entry && !it.terminal }?.let { current ->
            if (current.terminalIntent == null) current.state["endReason"] = payload.reason
            current.permitted.complete(false)
            current.timeout?.cancel()
            current.providedMediaJob?.cancel()
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
            try {
                current.ready.await()
                if (calls[id] === current) finish(id, current.incoming!!.intent!!.payload.reason)
            }
            catch (_: Exception) { if (calls[id] === current) terminal(id, "failed") }
        }
        current.endJob = work
        work.start()
    }
    internal fun canWarmIncoming(id: String): Boolean = calls[id]?.let { !it.terminal && !it.ending && !it.ringExpired && it.incoming?.intent == null } == true
    private val telecom = CallsManager(context).apply {
        registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
    }
    internal var coreAdmission = CoreCallAdmission { attributes, callbacks, admitted ->
        telecom.addCall(attributes, callbacks.answer, callbacks.disconnect, callbacks.activate, callbacks.deactivate, admitted)
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
    internal var policy = org.json.JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to true))
    val mediaAdapter = metadata?.getString("VoIPConversationsMediaAdapter") ?: "webrtc"
    private enum class Placement { PENDING, SUCCEEDED, FAILED }
    private class Call(val state: MutableMap<String, Any?>, val reservation: NativeIdentityReservations.Reservation,
                       val capabilities: ConversationCapabilities, val expiresAtMillis: Long?) {
        val ready = CompletableDeferred<CallControlScope>()
        val permitted = CompletableDeferred<Boolean>()
        var job: Job? = null
        var collectors: Job? = null
        var active = false
        var admitted = false
        var visible = false
        var endpoint: CallEndpointCompat? = null
        var media: NativeMediaOwner? = null
        var providedMedia: Map<String, Any?>? = null
        var providedMediaJob: Job? = null
        lateinit var audio: NativeAudioLifecycle
        val receipts = mutableMapOf<NativePerformedAction, NativeActionReceipt>()
        var holdingTerminal = false
        var nonActionEnd = false
        var localEndPending = false
        var holdRevision = 0L
        var muteRevision = 0L
        var placement = Placement.SUCCEEDED
        var timeout: Job? = null
        var ringExpired = false
        var endpoints: List<CallEndpointCompat> = emptyList()
        var incoming: NativeEndedIntake.Entry? = null
        var endJob: Job? = null
        var ending = false
        var terminal = false
        var terminalIntent: NativeEndedIntake.Intent? = null
        lateinit var disconnect: CoreCallDisconnect
        /** Why the call failed, when that is known before it ends. */
        var failure: EndedFailure? = null
    }
    private val identities = NativeIdentityReservations()
    internal var nativeMedia = AndroidNativeMedia.from(metadata?.getString("ExpoCallKitTelecomAndroidNativeMedia"))
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
    internal var incomingRingDeadline = IncomingRingDeadline()
    internal var platformRemovalDeadlineMillis = PLATFORM_REMOVAL_DEADLINE_MILLIS
    internal var capabilities = ConversationCapabilities(metadata?.getBoolean("ExpoCallKitTelecomSupportsHold", true) ?: true)
    internal var answerTimeoutMillis = (metadata?.getInt("ExpoCallKitTelecomFulfillAnswerCallTimeout", 30) ?: 30).coerceAtLeast(1).toLong() * 1000
    private var revision = 0L
    private var publishedRevision = 0L
    @Volatile var snapshot: Map<String, Any?> = mapOf("revision" to 0, "conversations" to emptyList<Any>())
        private set
    private var routeRevision = 0L
    private var routes: Map<String, Any?> = mapOf("revision" to 0, "routes" to emptyList<Any>(), "current" to null)
    internal val actions = StartPlacementCoordinator({ name, body -> emit(name, body) }, ::publish).apply {
        acceptUnhandled = metadata?.getString("ExpoCallKitTelecomUnhandledAction") == "accept"
    }
    private val audio = context.getSystemService(AudioManager::class.java)
    internal var proximity = ConversationProximity(proximityWakeLock(context))

    fun audioRoutes() = routes
    private fun publicState(id: String, call: Call): Map<String, Any?> = buildMap {
        putAll(call.state.filterKeys { it != "endReason" }.filterValues { it != null })
        actions.pending(id)?.let { put("pending", it) }
    }
    private fun publish() {
        refreshProximity()
        val states = calls.filterValues { it.visible }.map { (id, call) -> publicState(id, call) }.sortedBy { it["id"] as String }
        if (snapshot["conversations"] != states) {
            snapshot = mapOf("revision" to ++revision, "conversations" to states)
            ConversationService.refresh(context, states.filter { it["status"] != "ended" })
        }
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
            val batch = pendingTerminals.filterValues { !it.holdingTerminal }
            if (batch.isEmpty()) break
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
                current.visible = false
                if (current.disconnect.pending) ConversationService.ending(context, id)
                else releaseTerminal(id, current)
                refreshRoutes()
            }
            publish()
        } } finally { flushingTerminals = false }
    }
    private fun releaseTerminal(id: String, current: Call) {
        if (calls[id] !== current || !current.terminal || current.holdingTerminal) return
        calls.remove(id)
        identities.release(current.reservation)
        current.incoming?.let(endedIntake::removed)
        ConversationService.remove(context, id)
        refreshRoutes()
    }
    @Suppress("UNCHECKED_CAST")
    private fun signal(current: Call, stage: NativeMediaStage, measurements: Map<String, Long> = emptyMap(), reason: String? = null) {
        val id = (current.state["payload"] as? Map<String, Any?>)?.get("serverCallId") as? String ?: current.reservation.id
        NativeMediaSignals.emit(id, stage, measurements, reason = reason)
    }
    private fun call(id: String) = calls[id] ?: error("unknown_conversation")
    private fun live(current: Call): Boolean = calls[current.state["id"] as String] === current &&
        identities.isCurrent(current.reservation) && !current.terminal && !current.ending && !current.localEndPending && current.incoming?.intent == null
    private fun requireLive(current: Call) { check(live(current)) { "unknown_conversation" } }
    private suspend fun control(id: String): CallControlScope {
        val current = call(id)
        check(current.admitted) { "invalid_arguments" }
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
        if (calls[serverId]?.reservation?.direction == NativeCallDirection.OUTGOING) return failure("invalid_arguments")
        val duplicate = recent["event:$eventId"] ?: recent["call:$serverId"]
        if (duplicate != null) return duplicate.second
        (incomingTasks["event:$eventId"] ?: incomingTasks["call:$serverId"])?.let { return it.await() }
        val id = serverId
        if (calls.containsKey(id)) { control(id); return mapOf("status" to "reported", "id" to id) }
        val pending = CompletableDeferred<Map<String, Any?>>()
        incomingTasks["event:$eventId"] = pending; incomingTasks["call:$serverId"] = pending
        val reservation = identities.reserve(id, NativeCallDirection.INCOMING)
        val ownership = endedIntake.reserve(serverId, id)
        val outcome = try {
            val current = add(id, caller.asParticipant(), payload["hasVideo"] == true, payload, caller.coreAddress(), ownership, reservation)
            val appFilter = if (ownership.intent == null) incomingPolicy.custom(payload) else null
            val filter = appFilter ?: incomingPolicy.system(payload)
            if (ownership.intent != null) {
                current.permitted.complete(false)
                drainEnded(current)
                mapOf("status" to "reported", "id" to id)
            } else if (current.admitted && current.ringExpired) {
                // Expiry can finish while policy evaluation is suspended. Keep the report's
                // dedupe result without presenting or warming a call that already expired.
                current.permitted.complete(false)
                mapOf("status" to "reported", "id" to id)
            } else if (calls[id] !== current || current.terminal) failure("conversation") else {
                current.permitted.complete(filter == null)
                // A DND-suppressed notification still records repeat-caller history when cancelled.
                // App block-list/other rejections are never presented.
                if (appFilter == null) {
                    if (filter == null) ConversationService.show(context, id, publicState(id, current))
                    else ConversationService.recordFiltered(context, publicState(id, current))
                }
                if (filter != null && current.state["status"] == "ringing") {
                    closeFiltered(id, filter.reason)
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
            withContext(NonCancellable) { if (calls[id]?.reservation === reservation) runCatching { finish(id, "failed") } }
            failure("conversation")
        }
        if (calls[id]?.reservation !== reservation) identities.release(reservation)
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
    private suspend fun closeFiltered(id: String, reason: NativeIncomingFilterReason) {
        call(id).media?.close("filtered")
        finish(id, "failed", EndedFailure.filtered(reason))
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
                    closeFiltered(id, filter.reason)
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
        val id = options["id"] as? String ?: error("invalid_arguments")
        val reservation = identities.reserve(id, NativeCallDirection.OUTGOING)
        val current = create(reservation, to, options["video"] == true, null)
        current.placement = Placement.PENDING
        try {
            val report = actions.place(id, current.state.toMap())
            requireLive(current)
            current.placement = Placement.SUCCEEDED
            add(id, to, options["video"] == true, null, reservation = reservation, existing = current)
            requireLive(current)
            NativeActionReceipt(id, NativeActionOrigin.APP, NativePerformedAction.Start).perform({}, ::publish, emit)
            report?.let { this.report(id, mapOf("type" to if (it == PlacementConnectionReport.CONNECTED) "connected" else "connecting")) }
            return id
        } catch (error: Exception) {
            current.placement = Placement.FAILED
            withContext(NonCancellable) {
                if (calls[id] === current && identities.isCurrent(reservation) && !current.localEndPending) {
                    current.failure = EndedFailure.ofHandler(error)
                    if (current.admitted) runCatching { finish(id, "failed", current.failure) }
                    if (calls[id] === current) terminal(id, "failed")
                }
            }
            throw error
        }
    }
    private suspend fun add(id: String, member: Map<String, Any?>, video: Boolean, payload: Map<String, Any?>?, address: Uri? = null,
                            incomingOwnership: NativeEndedIntake.Entry? = null,
                            reservation: NativeIdentityReservations.Reservation, existing: Call? = null): Call {
        val incoming = payload != null
        val call = existing ?: create(reservation, member, video, payload)
        call.incoming = incomingOwnership
        if (!incoming) call.permitted.complete(true)
        return admit(id, call, member, video, incoming, address)
    }
    private fun create(reservation: NativeIdentityReservations.Reservation, member: Map<String, Any?>, video: Boolean,
                       payload: Map<String, Any?>?): Call {
        val id = reservation.id
        val incoming = payload != null
        val supported = capabilities.copy(supportsTones = nativeMedia == AndroidNativeMedia.LIVEKIT)
        val call = Call(linkedMapOf("id" to id, "direction" to if (incoming) "incoming" else "outgoing",
            "status" to if (incoming) "ringing" else "dialing", "members" to listOf(member),
            "isMuted" to false, "isVideo" to video, "payload" to payload?.filterKeys { it != "media" },
            "capabilities" to supported.snapshot()), reservation, supported, (payload?.get("expiresAtMillis") as? Number)?.toLong())
        call.disconnect = CoreCallDisconnect(jobs, platformRemovalDeadlineMillis)
        call.visible = !incoming
        calls[id] = call
        call.audio = NativeAudioLifecycle({ live(call) && call.active }, { audio.mode }) { event ->
            val type = when (event) { NativeAudioEvent.ACTIVATED -> "audioActivated"
                NativeAudioEvent.DEACTIVATED -> "audioDeactivated"; NativeAudioEvent.ACTIVATION_MISSING -> "audioActivationMissing" }
            if (event == NativeAudioEvent.ACTIVATED) {
                signal(call, NativeMediaStage.AUDIO_MODE_READY)
                signal(call, NativeMediaStage.AUDIO_AUTHORIZED, mapOf("audioMode" to audio.mode.toLong(), "coreActive" to 1L, "endpointObserved" to if (call.endpoint != null) 1L else 0L))
            }
            if (event == NativeAudioEvent.ACTIVATION_MISSING) signal(call, NativeMediaStage.AUDIO_WAIT_EXPIRED, mapOf("audioMode" to audio.mode.toLong(), "hasEndpoint" to if (call.endpoint != null) 1L else 0L, "active" to if (call.active) 1L else 0L))
            emit("conversationEvent", mapOf("type" to type, "payload" to mapOf("conversationId" to id)))
        }
        if (payload != null) prepareMedia(id, payload, false)
        return call
    }
    private suspend fun admit(id: String, call: Call, member: Map<String, Any?>, video: Boolean, incoming: Boolean, address: Uri?): Call {
        val handle = (member["phoneNumber"] ?: member["email"] ?: member["id"]).toString()
        val preferredEndpoint = preferredStartingEndpoint(video)
        signal(call, NativeMediaStage.ROUTE_PREFERENCE_REQUESTED, mapOf(
            "cached" to if (preferredEndpoint != null) 1L else 0L,
            "type" to (preferredEndpoint?.type?.toLong() ?: 0L),
            "availableCount" to startingEndpoints.size.toLong()))
        val attributes = CallAttributesCompat((member["displayName"] ?: handle).toString(), address ?: Uri.fromParts("sip", handle, null),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            call.capabilities.telecom, preferredStartingCallEndpoint = preferredEndpoint)
        call.job = jobs.launch {
            try {
                signal(call, NativeMediaStage.CORE_ADD_REQUESTED)
                coreAdmission.add(attributes, CoreCallCallbacks(
                    answer = { if (calls[id] === call) answerFromSystem(id) },
                    disconnect = { cause -> withTimeout(4500) { if (calls[id] === call) {
                        if (call.terminal) Unit
                        else if (call.ending || call.incoming?.intent != null) terminal(id, call.state["endReason"] as? String ?: call.incoming?.intent?.payload?.reason ?: "failed")
                        else if (!call.disconnect.started && cause.code == DisconnectCause.REMOTE) {
                            call.nonActionEnd = true
                            terminal(id, "remoteEnded")
                        } else if (!call.disconnect.started && cause.code == DisconnectCause.ERROR) {
                            call.nonActionEnd = true
                            call.failure = EndedFailure.FRAMEWORK_RESET
                            terminal(id, "failed")
                        }
                        else native(id, NativePerformedAction.End, NativeActionOrigin.SYSTEM)
                    } } },
                    activate = { withTimeout(4500) { check(call.permitted.await());
                        // Initial activation remains valid when this call does not offer hold/resume.
                        if (calls[id] === call && call.capabilities.supportsHold) hold(id, false, NativeActionOrigin.SYSTEM) } },
                    deactivate = { withTimeout(4500) { if (calls[id] === call) hold(id, true, NativeActionOrigin.SYSTEM) } })) {
                    if (calls[id] !== call || !identities.isCurrent(call.reservation) || call.terminal) {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            withContext(NonCancellable) {
                                if (disconnectCore(call, this@add, DisconnectCause(DisconnectCause.LOCAL)) == CoreDisconnectOutcome.REMOVED)
                                    signal(call, NativeMediaStage.CORE_LATE_DISCONNECTED)
                            }
                        }
                        return@add
                    }
                    call.admitted = true
                    call.incoming?.let(endedIntake::admitted)
                    signal(call, NativeMediaStage.CORE_ADMITTED)
                    signal(call, NativeMediaStage.REPORTED)
                    publish()
                    if (!incoming) ConversationService.show(context, id, publicState(id, call))
                    call.timeout = jobs.launch {
                        if (incoming) incomingRingDeadline.await(call.expiresAtMillis, incomingTimeoutMillis)
                        else delay((metadata?.getInt("ExpoCallKitTelecomOutgoingCallTimeout", 60) ?: 60).coerceAtLeast(1).toLong()*1000)
                        if (calls[id] === call && call.state["connectedAt"] == null) {
                            if (incoming) call.ringExpired = true
                            terminateLater(id, "unanswered")
                        }
                    }
                    val controls = this
                    call.collectors = jobs.launch {
                        try { coroutineScope {
                    launch { controls.availableEndpoints.collect { call.endpoints = it; updateRoutes(id, null) } }
                    launch { controls.currentCallEndpoint.collect { updateRoutes(id, it) } }
                    launch { controls.isMuted.onEach { muted ->
                        signal(call, NativeMediaStage.CORE_MUTE_OBSERVED, mapOf("muted" to if (muted) 1L else 0L))
                    }.coreMuteChanges().collect { muted ->
                        if (live(call) && call.state["isMuted"] != muted) {
                            try { mute(id, muted, NativeActionOrigin.SYSTEM) }
                            catch (error: Exception) {
                                if (error !is CancellationException && live(call)) call.failure = EndedFailure.MEDIA
                                throw error
                            }
                        }
                    } }
                        } } catch (error: Exception) {
                            if (error !is CancellationException && live(call)) terminateLater(id, "failed", call.failure ?: EndedFailure.FRAMEWORK_RESET)
                        }
                    }
                    // Install ownership/cleanup before waking incoming or retained end work.
                    call.ready.complete(this)
                }
            } catch (e: Exception) { call.ready.completeExceptionally(e) }
            finally {
                call.collectors?.cancel()
                // Core 1.0.1 closes this lifetime even when disconnect returns an error.
                // The independently owned retry must settle before publishing the end.
                withContext(NonCancellable) { call.disconnect.awaitCompletion() }
                if (calls[id] === call) {
                    if (call.admitted && call.state["endReason"] == null) call.failure = call.failure ?: EndedFailure.FRAMEWORK_RESET
                    terminal(id, call.state["endReason"] as? String ?: "failed",
                        call.nonActionEnd || !call.receipts.containsKey(NativePerformedAction.End))
                }
            }
        }
        try { withTimeout(6000) { call.ready.await() } }
        catch (error: Exception) { if (!call.terminal) call.job?.cancel(); throw error }
        return call
    }
    internal fun provideMedia(id: String, media: Map<String, Any?>) {
        ConversationMediaRouter({ nativeMedia }, { key -> calls[key]?.let { current ->
            ConversationMediaRouter.Target(current.reservation.direction, live(current) && !current.ringExpired,
                current.state["status"] == "ringing", current.media?.hasSession == true || current.providedMediaJob != null,
                { credentials, route -> supplyMedia(current, credentials, route) })
        } }, { key, reason -> NativeMediaSignals.emit(key, NativeMediaStage.MEDIA_IGNORED, reason = reason.reason) })
            .provide(id, media)
    }

    private fun supplyMedia(current: Call, media: Map<String, Any?>, route: MediaProvisionRoute) {
        signal(current, NativeMediaStage.MEDIA_PROVIDED)
        if (route == MediaProvisionRoute.ANSWER) { current.providedMedia = media; return }
        val id = current.reservation.id
        prepareMedia(id, mapOf("serverCallId" to id, "media" to media))
        val owner = checkNotNull(current.media)
        val work = jobs.launch(start = CoroutineStart.LAZY) {
            try {
                val controls = current.ready.await()
                if (!live(current) || current.state["status"] == "held") return@launch
                if (!current.active) checked(controls.setActive())
                if (!live(current) || current.state["status"] == "held") return@launch
                current.active = true
                if (current.state["connectedAt"] == null) current.state["status"] = "connecting"
                signal(current, NativeMediaStage.AUDIO_WAIT_STARTED)
                owner.answered()
                publish()
            } catch (error: Exception) {
                if (error !is CancellationException && live(current)) terminateLater(id, "failed", EndedFailure.MEDIA)
            }
        }
        current.providedMediaJob = work
        work.start()
    }

    private fun prepareMedia(id: String, payload: Map<String, Any?>, prewarm: Boolean = true) {
        val call = call(id)
        fun disconnected(reason: NativeMediaDisconnection) {
            if (calls[id] !== call || !live(call)) return
            if (call.reservation.direction == NativeCallDirection.INCOMING && call.state["status"] == "ringing") {
                if (call.media?.prewarmLost() == true) signal(call, NativeMediaStage.PREWARM_LOST)
            } else when (reason) {
                NativeMediaDisconnection.REMOTE_ENDED -> terminateLater(id, "remoteEnded")
                // Our own releases retire observation first; a live observed disconnect is unexpected.
                NativeMediaDisconnection.CLIENT_INITIATED,
                NativeMediaDisconnection.FAILURE -> terminateLater(id, "failed", EndedFailure.MEDIA)
            }
        }
        run {
            call.media = call.media ?: NativeMediaOwner(nativeMedia, call.audio, initiallyMuted = call.state["isMuted"] == true) { answerMedia -> NativeMediaController(context,
                if (answerMedia == null) payload else payload + ("media" to answerMedia),
                policy, jobs, failed = { disconnected(NativeMediaDisconnection.FAILURE) }, connected = {
                if (calls[id] === call) {
                    call.timeout?.cancel()
                    call.state["connectedAt"] = call.state["connectedAt"] ?: Instant.now().toString()
                    if (call.state["status"] != "held") markConnected(call)
                    publish()
                }
            }, disconnected = ::disconnected) }
            if (prewarm) call.media?.prewarm()
        }
    }

    private fun observeControl(id: String, action: NativePerformedAction, origin: NativeActionOrigin, point: NativeControlPoint) {
        val observer = controlObserver ?: return
        val (kind, closed) = when (action) {
            is NativePerformedAction.Hold -> NativeControlKind.HOLD to action.held
            is NativePerformedAction.Mute -> NativeControlKind.MUTE to action.muted
            else -> return
        }
        runCatching { observer.observed(NativeControlObservation(id, kind, closed, point, android.os.SystemClock.elapsedRealtimeNanos(), origin)) }
    }

    private suspend fun native(id: String, action: NativePerformedAction, origin: NativeActionOrigin,
                               request: (suspend () -> Unit)? = null) {
        val current = call(id)
        observeControl(id, action, origin, NativeControlPoint.REQUESTED)
        @Suppress("UNCHECKED_CAST") val serverId = (current.state["payload"] as? Map<String, Any?>)?.get("serverCallId") as? String
        val previous = current.receipts[action]
        val reusable = when (action) {
            is NativePerformedAction.Hold -> previous?.controlRevision == current.holdRevision
            is NativePerformedAction.Mute -> previous?.controlRevision == current.muteRevision
            else -> true
        }
        val receipt = previous?.takeIf { reusable } ?: run {
            val revision = when (action) {
                is NativePerformedAction.Hold -> ++current.holdRevision
                is NativePerformedAction.Mute -> ++current.muteRevision
                else -> 0L
            }
            NativeActionReceipt(id, origin, action, serverId, current.state["status"] == "ringing", revision)
                .also {
                    current.receipts[action] = it
                    if (action == NativePerformedAction.Answer) signal(current, NativeMediaStage.ANSWER_REQUESTED)
                }
        }
        receipt.retain()
        if (action == NativePerformedAction.End) { current.localEndPending = true; current.timeout?.cancel(); refreshProximity() }
        fun latest(): Boolean = when (action) {
            is NativePerformedAction.Hold -> receipt.controlRevision == current.holdRevision
            is NativePerformedAction.Mute -> receipt.controlRevision == current.muteRevision
            else -> true
        }
        try {
            val decision = receipt.decide(ConversationActionDecision(jobs, { stage, reason -> signal(current, stage, reason = reason) }))
            val canRefuse = receipt.conversationAction?.canRefuse(origin.wireValue) == true
            if (decision is ConversationActionResult.Refuse && canRefuse) {
                if (action == NativePerformedAction.Answer) {
                    withContext(NonCancellable) { if (live(current)) finish(id, decision.validEndReason(), EndedFailure.NOT_PLACED) }
                }
                throw decision.rejection()
            }
            if (!latest()) return
            if (decision is ConversationActionResult.Refuse && origin == NativeActionOrigin.SYSTEM &&
                (action is NativePerformedAction.Hold || action is NativePerformedAction.Mute)) receipt.observedFromSystem()
            if (action != NativePerformedAction.End) requireLive(current)
            request?.invoke()
            if (action is NativePerformedAction.Hold && request != null)
                observeControl(id, action, origin, NativeControlPoint.PLATFORM_ACKNOWLEDGED)
            if (!latest()) return
            receipt.performWhile(::latest, {
                check(calls[id] === current && identities.isCurrent(current.reservation) && current.incoming?.intent == null) { "unknown_conversation" }
                if (action != NativePerformedAction.End) requireLive(current)
                else check(!current.nonActionEnd && (!current.terminal || current.holdingTerminal)) { "unknown_conversation" }
                when (action) {
                    NativePerformedAction.Answer -> { current.active = true; joined(id, (decision as? ConversationActionResult.Proceed)?.media) }
                    NativePerformedAction.End -> terminal(id, "local", false)
                    is NativePerformedAction.Hold -> {
                        current.active = !action.held
                        if (!action.held && current.state["connectedAt"] != null) markConnected(current)
                        else current.state["status"] = if (action.held) "held" else "connecting"
                        if (action.held) { current.audio.inactive(); publish(); current.media?.pause() }
                        else if (current.media != null) current.media?.resume() else current.audio.coreActivated()
                        refreshRoutes()
                    }
                    is NativePerformedAction.Mute -> {
                        if (action.muted) current.media?.mute() else if (current.active) current.media?.unmute() else current.media?.clearMute()
                        requireLive(current)
                        if (!latest()) return@performWhile
                        if (current.active && origin == NativeActionOrigin.APP) audio.isMicrophoneMute = action.muted
                        if (current.active && origin == NativeActionOrigin.APP)
                            observeControl(id, action, origin, NativeControlPoint.PLATFORM_ACKNOWLEDGED)
                        current.state["isMuted"] = action.muted
                    }
                    NativePerformedAction.Start, is NativePerformedAction.Tones -> error("invalid_arguments")
                }
            }, ::publish) { name, body ->
                emit(name, body)
                if (action == NativePerformedAction.End) { current.holdingTerminal = false; flushTerminals() }
            }
            observeControl(id, action, origin, NativeControlPoint.COMPLETED)
        } finally {
            val remaining = receipt.release()
            if (remaining == 0 && current.receipts[action] === receipt) current.receipts.remove(action)
            if (action == NativePerformedAction.End && remaining == 0) current.localEndPending = false
            if (action == NativePerformedAction.End && current.holdingTerminal) { current.holdingTerminal = false; publish() }
        }
    }
    internal suspend fun join(id: String, origin: NativeActionOrigin = NativeActionOrigin.APP) {
        val current = call(id)
        requireLive(current)
        if (current.state["status"] == "preview") error("unsupported")
        require(current.state["status"] == "ringing") { "invalid_arguments" }
        check(current.permitted.await()) { "Incoming call filtered" }
        requireLive(current)
        current.timeout?.cancel()
        native(id, NativePerformedAction.Answer, origin) {
            checked(control(id).answer(if (current.state["isVideo"] == true) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL))
        }
    }
    /** Same entry point used by Core's onAnswer callback, including app-request echoes. */
    internal suspend fun answerFromSystem(id: String): Unit = withTimeout(4500) {
        val current = call(id)
        check(current.permitted.await()) { "Incoming call filtered" }
        if (current.state["status"] == "ringing") {
            current.timeout?.cancel()
            native(id, NativePerformedAction.Answer, NativeActionOrigin.SYSTEM)
        }
    }
    private fun markConnected(current: Call) {
        if (current.state["status"] != "connected") {
            current.state["status"] = "connected"
            signal(current, NativeMediaStage.CONNECTED)
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun joined(id: String, media: Map<String, Any?>? = null) {
        val current = call(id)
        requireLive(current)
        val answerMedia = media ?: current.providedMedia
        current.providedMedia = null
        signal(current, NativeMediaStage.ANSWER_ACCEPTED)
        current.state["status"] = "connecting"
        current.timeout?.cancel()
        current.timeout = jobs.launch {
            delay(answerTimeoutMillis)
            if (calls[id] === current && current.state["connectedAt"] == null) terminateLater(id, "failed", EndedFailure.CONNECT_TIMED_OUT)
        }
        ConversationService.show(context, id, publicState(id, current))
        signal(current, NativeMediaStage.AUDIO_WAIT_STARTED)
        if (current.media == null && answerMedia != null) {
            (current.state["payload"] as? Map<String, Any?>)?.let { prepareMedia(id, it, prewarm = false) }
        }
        if (current.media != null) current.media?.answered(answerMedia) else current.audio.coreActivated()
        refreshRoutes()
        publish()
    }
    internal suspend fun end(id: String, origin: NativeActionOrigin = NativeActionOrigin.APP) {
        val current = call(id)
        if (current.terminal) return
        if (current.ending || current.nonActionEnd) return
        // Own a local end before cancelling placement can resume its failure cleanup.
        current.localEndPending = true
        current.providedMediaJob?.cancel()
        if (current.placement == Placement.PENDING) { current.placement = Placement.FAILED; actions.cancel(id) }
        native(id, NativePerformedAction.End, origin) {
            check(calls[id] === current && identities.isCurrent(current.reservation) && !current.terminal &&
                !current.ending && !current.nonActionEnd && current.incoming?.intent == null) { "unknown_conversation" }
            current.state["endReason"] = "local"
            if (current.admitted) startCoreRemoval(current, current.ready.await(), DisconnectCause(DisconnectCause.LOCAL))
        }
    }
    internal fun retryPlatformRemoval(id: String) { calls[id]?.takeIf { it.terminal }?.disconnect?.retryNow() }
    internal suspend fun hold(id: String, held: Boolean, origin: NativeActionOrigin = NativeActionOrigin.APP) {
        val current = call(id)
        requireLive(current)
        current.capabilities.requireHold()
        if ((current.state["status"] == "held") == held && current.receipts.keys.none { it is NativePerformedAction.Hold }) return
        native(id, NativePerformedAction.Hold(held), origin,
            if (origin == NativeActionOrigin.APP) suspend { checked(if (held) control(id).setInactive() else control(id).setActive()) } else null)
    }
    internal suspend fun mute(id: String, muted: Boolean, origin: NativeActionOrigin = NativeActionOrigin.APP) {
        val current = call(id)
        requireLive(current)
        if (current.state["isMuted"] == muted && current.receipts.keys.none { it is NativePerformedAction.Mute }) return
        native(id, NativePerformedAction.Mute(muted), origin)
    }
    suspend fun tones(id: String, digits: String) {
        NativeDtmfDigit.parse(digits)
        val current = call(id)
        requireLive(current)
        current.capabilities.requireTones()
        fun requireActive() { requireLive(current); check(current.active && current.state["status"] != "held") { "invalid_arguments" } }
        requireActive()
        // Separate presses with identical digits are separate actions, never callback echoes.
        NativeActionReceipt(id, NativeActionOrigin.APP, NativePerformedAction.Tones(digits)).perform({
            (current.media ?: error("invalid_arguments")).playTones(digits)
            requireActive()
        }, ::publish, emit)
    }
    suspend fun report(id: String, report: Map<String, Any?>) {
        val current = call(id)
        if (report["type"] != "ended") requireLive(current)
        if (report["type"] == "connecting" || report["type"] == "connected") {
            val fact = if (report["type"] == "connected") PlacementConnectionReport.CONNECTED else PlacementConnectionReport.CONNECTING
            if (actions.deferReport(id, fact)) return
            check(current.placement == Placement.SUCCEEDED) { "invalid_arguments" }
        }
        when (report["type"]) {
            "connecting" -> if (current.state["status"] in listOf("dialing", "connecting")) current.state["status"] = "connecting"
            "connected" -> {
                require(current.state["status"] != "ringing") { "invalid_arguments" }
                if (current.state["status"] != "held") { if (!current.active) checked(control(id).setActive()); requireLive(current); current.active = true; if (current.media == null) current.audio.coreActivated(); markConnected(current) }
                current.timeout?.cancel()
                current.state["connectedAt"] = current.state["connectedAt"] ?: Instant.now().toString()
            }
            "updated" -> { report["members"]?.let { current.state["members"] = it }; report["video"]?.let { current.state["isVideo"] = it } }
            "ended" -> { finish(id, report["reason"] as? String ?: error("invalid_arguments"), EndedFailure.SERVER); return }
            else -> error("invalid_arguments")
        }
        publish()
    }
    private fun terminateLater(id: String, reason: String, failure: EndedFailure? = null) {
        val expected = calls[id] ?: return
        jobs.launch {
            try { if (calls[id] === expected) finish(id, reason, failure) }
            catch (_: Exception) { if (calls[id] === expected) terminal(id, "failed") }
        }
    }
    /** Ends the call. A `failed` end carries [failure], unless a server end signal ended the call first. */
    private suspend fun finish(id: String, reason: String, failure: EndedFailure? = null) {
        require(reason in setOf("local", "remoteEnded", "failed", "unanswered", "answeredElsewhere", "declinedElsewhere")) { "invalid_arguments" }
        val current = call(id)
        if (current.terminal) return
        if (current.disconnect.started) {
            val intent = current.incoming?.intent
            if (current.nonActionEnd && intent == null) return
            // A reported end suppresses the pending local receipt while sharing its
            // Core request. Retained server intake also keeps its reason/event ownership.
            current.nonActionEnd = true
            current.ending = true
            refreshProximity()
            current.terminalIntent = intent
            current.state["endReason"] = intent?.payload?.reason ?: reason
            if (intent != null) current.failure = EndedFailure.SERVER.takeIf { intent.payload.reason == "failed" }
            else if (reason == "failed") current.failure = current.failure ?: failure
            return
        }
        if (current.ending) return
        current.nonActionEnd = true
        current.ending = true
        refreshProximity()
        if (reason == "failed") current.failure = current.failure ?: failure
        try {
            if (!current.admitted) { terminal(id, reason); return }
            val controls = current.ready.await()
            if (calls[id] !== current || current.terminal) return
            // Claim the actual native request before its callback or SDK publication can reenter.
            val intent = current.incoming?.intent
            current.terminalIntent = intent
            val endedReason = intent?.payload?.reason ?: reason
            // A server end signal ends the call for its own reason.
            if (intent != null) current.failure = EndedFailure.SERVER.takeIf { endedReason == "failed" }
            val cause = intent?.payload?.disconnectCause() ?: DisconnectCause(when (reason) {
                "local", "failed" -> DisconnectCause.LOCAL
                "unanswered" -> DisconnectCause.MISSED
                "answeredElsewhere" -> if (android.os.Build.VERSION.SDK_INT >= 34) DisconnectCause.REMOTE else DisconnectCause.ANSWERED_ELSEWHERE
                else -> DisconnectCause.REMOTE
            }, reason)
            current.state["endReason"] = endedReason
            startCoreRemoval(current, controls, cause)
            if (calls[id] === current) terminal(id, current.state["endReason"] as? String ?: endedReason)
        } catch (error: Exception) { current.ending = false; throw error }
    }
    private suspend fun disconnectCore(current: Call, controls: CallControlScope, cause: DisconnectCause): CoreDisconnectOutcome {
        return startCoreRemoval(current, controls, cause).await()
    }
    private fun startCoreRemoval(current: Call, controls: CallControlScope, cause: DisconnectCause): Deferred<CoreDisconnectOutcome> {
        val started = current.disconnect.started
        val completion = current.disconnect.start { controls.disconnect(cause) }
        if (!started) jobs.launch {
            val outcome = completion.await()
            if (outcome == CoreDisconnectOutcome.DEADLINE_EXPIRED) {
                signal(current, NativeMediaStage.PLATFORM_REMOVAL_FAILED, mapOf(
                    "attemptCount" to current.disconnect.attempts.toLong(),
                    "lastCoreErrorCode" to current.disconnect.lastErrorCode.toLong()))
            }
            current.job?.cancel()
            releaseTerminal(current.reservation.id, current)
            publish()
        }
        return completion
    }
    private fun terminal(id: String, reason: String, publishNow: Boolean = true) {
        val current = calls[id] ?: return
        if (current.terminal) return
        current.terminal = true
        current.active = false
        current.providedMediaJob?.cancel()
        current.providedMedia = null
        current.ready.completeExceptionally(ConversationFailure("unknown_conversation"))
        current.holdingTerminal = !publishNow
        current.audio.close()
        current.permitted.complete(false)
        current.timeout?.cancel()
        current.collectors?.cancel()
        current.state["status"] = "ended"
        current.state["ended"] = conversationEnd(Instant.now().toString(), reason, current.failure)
        current.visible = true
        pendingTerminals[id] = current
        current.media?.close(if (reason == "local" && current.state["connectedAt"] == null) "declined" else "ended")
        actions.cancel(id)
        if (publishNow) publish()
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
            CallEndpointCompat.TYPE_BLUETOOTH -> "bluetooth"; CallEndpointCompat.TYPE_WIRED_HEADSET -> "headphones"; else -> "other" })
    private fun updateRoutes(id: String, endpoint: CallEndpointCompat?) {
        val current = calls[id] ?: return
        if (endpoint != null) {
            current.endpoint = endpoint
            @Suppress("UNCHECKED_CAST") val serverId = (current.state["payload"] as? Map<String, Any?>)?.get("serverCallId") as? String
            serverId?.let { NativeMediaSignals.emit(it, NativeMediaStage.ENDPOINT_READY, mapOf("type" to endpoint.type.toLong())) }
        }
        refreshRoutes()
    }
    private fun refreshProximity() {
        val active = calls.values.firstOrNull { it.active && live(it) && it.state["status"] != "held" }
        proximity.update(active != null, active?.endpoint?.type)
    }
    private fun refreshRoutes() {
        refreshProximity()
        val selected = calls.values.firstOrNull { it.active && !it.terminal } ?: calls.values.firstOrNull { it.admitted && !it.terminal }
        val available = selected?.endpoints?.map(::route) ?: emptyList()
        val current = selected?.endpoint?.let(::route)
        if (routes["routes"] == available && routes["current"] == current) return
        routes = mapOf("revision" to ++routeRevision, "routes" to available, "current" to current)
        emit("audioRoutesChanged", routes)
    }
    suspend fun selectRoute(id: String) {
        val current = calls.values.firstOrNull { it.active && !it.terminal } ?: error("unknown_conversation")
        val endpoint = current.endpoints.firstOrNull { it.identifier.toString() == id } ?: error("invalid_arguments")
        val controls = current.ready.await()
        requireLive(current)
        checked(controls.requestEndpointChange(endpoint))
        requireLive(current)
    }
}
