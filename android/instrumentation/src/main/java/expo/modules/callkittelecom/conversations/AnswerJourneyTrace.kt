package expo.modules.callkittelecom.conversations

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

internal enum class AnswerJourneyPhase {
    FIXTURE_STARTED, INCOMING_REPORTED, WAIT_STARTED, WAIT_COMPLETED, WAIT_TIMED_OUT, WAIT_CANCELLED,
    DISPLAY_WAKE_STARTED, DISPLAY_READY,
    NOTIFICATION_SHADE_OPEN_STARTED, NOTIFICATION_SHADE_OPEN_COMPLETED, SYSTEMUI_ACTION_TAPPED, SHADE_COLLAPSED,
    ACTIVITY_CREATED, ACTIVITY_NEW_INTENT,
    ACTIVITY_RESUMED, ACTIVITY_DESTROYED, TOKEN_CONSUMED_AT_RECEIVE_RETURN, NATIVE_ANSWER_STARTED,
    CORE_ANSWER_STARTED, CORE_ANSWER_RETURNED, CORE_SET_ACTIVE_STARTED, CORE_SET_ACTIVE_RETURNED,
    CORE_ADMITTED,
    TELECOM_READ_STARTED, TELECOM_READ_RETURNED, TELECOM_ACTIVE, SDK_SNAPSHOT,
    SDK_SNAPSHOT_CONNECTING, SDK_SNAPSHOT_CONNECTED,
    ANSWER_PERFORMED, BODY_COMPLETED, CLEANUP_STARTED,
}

internal enum class AnswerJourneyWait {
    DISPLAY_ASLEEP, DISPLAY_READY, SYSTEMUI_ACTION, INCOMING_NOTIFICATION, ONGOING_NOTIFICATION, APP_BACKGROUND, ACTIVITY_FOREGROUND,
    AUDIO_ROUTE, RECEIVER_ROUTE, SNAPSHOT_ANSWERED, TELECOM_ACTIVE, ANSWER_PERFORMED,
    CONTENT_OVERRIDE, ACTIVITY_RECREATED, PLATFORM_REMOVED, ACTIVITY_DESTROYED,
}

internal data class AnswerLaunchDiagnostics(val linesRead: Int, val packageBlockedLines: Int,
    val conversationBlockedLines: Int, val balBlockMentions: Int, val fixtureActivities: Int)

internal enum class AnswerEndpointPhase { AVAILABLE_ENDPOINTS, CURRENT_ENDPOINT }
internal const val ANSWER_ENDPOINT_LIMIT = 32
internal const val ANSWER_ENDPOINT_TYPE_LIMIT = 8

/** Bounded test observations only. Tokens receive local ordinals and are never serialized. */
internal class AnswerJourneyTrace(val conversationId: String, private val now: () -> Long) {
    private data class Event(val phase: AnswerJourneyPhase, val nanos: Long, val wait: AnswerJourneyWait?,
        val token: Int?, val tokenPresent: Boolean?, val state: String?, val conversationMatches: Boolean?)
    private val started = now()
    private val events = mutableListOf<Event>()
    private val tokens = mutableMapOf<String, Int>()
    private val first = linkedMapOf<AnswerJourneyPhase, Event>()
    private data class EndpointEvent(val phase: AnswerEndpointPhase, val nanos: Long,
        val types: List<Int>, val count: Int)
    private val endpoints = mutableListOf<EndpointEvent>()
    private var droppedEndpoints = 0
    private var endpointAdmissionNanos: Long? = null
    private var endpointStopNanos: Long? = null
    private var dropped = 0
    private var currentToken: Int? = null
    var lastTimeout: AnswerJourneyWait? = null
        private set
    private var firstTimeout: AnswerJourneyWait? = null
    var launchDiagnostics: AnswerLaunchDiagnostics? = null

    fun mark(phase: AnswerJourneyPhase, wait: AnswerJourneyWait? = null, atNanos: Long = now(),
        token: Int? = currentToken, tokenPresent: Boolean? = null, state: String? = null,
        conversationMatches: Boolean? = null) {
        if (events.size == 256) { events.removeAt(64); dropped++ }
        val event = Event(phase, atNanos, wait, token, tokenPresent, state, conversationMatches)
        events += event
        first.putIfAbsent(phase, event)
        if (phase == AnswerJourneyPhase.CORE_ADMITTED && endpointAdmissionNanos == null && endpointStopNanos == null)
            endpointAdmissionNanos = atNanos
        if (phase == AnswerJourneyPhase.WAIT_TIMED_OUT) {
            if (endpointStopNanos == null) endpointStopNanos = atNanos
            if (firstTimeout == null) firstTimeout = wait
            lastTimeout = wait
        }
    }

    fun endpoint(phase: AnswerEndpointPhase, types: List<Int>) {
        if (endpointAdmissionNanos == null || endpointStopNanos != null) return
        if (endpoints.size == ANSWER_ENDPOINT_LIMIT) { droppedEndpoints++; return }
        endpoints += EndpointEvent(phase, now(), types.take(ANSWER_ENDPOINT_TYPE_LIMIT), types.size)
    }

    fun intent(phase: AnswerJourneyPhase, intent: Intent): Int? {
        if (intent.getStringExtra(ConversationNotificationIntents.EXTRA_CONVERSATION_ID) != conversationId) {
            mark(phase, token = null, conversationMatches = false)
            return null
        }
        val token = intent.getStringExtra("expo.modules.callkittelecom.answerToken")
        val correlation = token ?: intent.data?.takeIf { it.scheme == "voip-conversation" }?.lastPathSegment
        currentToken = correlation?.let { tokens.getOrPut(it) { tokens.size + 1 } }
        mark(phase, tokenPresent = token != null, conversationMatches = true)
        return currentToken
    }

    fun consumed(token: Int?) = mark(AnswerJourneyPhase.TOKEN_CONSUMED_AT_RECEIVE_RETURN, token = token)

    fun json(testName: String, succeeded: Boolean): String {
        val values = events.sortedBy { it.nanos }
        val phases = JSONArray()
        var previous = started
        for (event in values) {
            phases.put(JSONObject().apply {
                put("phase", event.phase.name)
                put("elapsedMs", (event.nanos - started) / 1_000_000.0)
                put("sincePreviousMs", (event.nanos - previous) / 1_000_000.0)
                event.wait?.let { put("wait", it.name) }
                event.token?.let { put("tokenOrdinal", it) }
                event.tokenPresent?.let { put("tokenPresent", it) }
                event.state?.let { put("snapshotState", it) }
                event.conversationMatches?.let { put("activityConversationMatches", it) }
            })
            previous = event.nanos
        }
        return JSONObject().apply {
            put("test", testName); put("succeeded", succeeded)
            put("elapsedMs", (now() - started) / 1_000_000.0)
            put("lastPhase", values.lastOrNull()?.phase?.name ?: "NONE")
            put("lastReachedPhase", values.lastOrNull { it.wait == null }?.phase?.name ?: "NONE")
            firstTimeout?.let { put("timedOutWait", it.name) }
            lastTimeout?.let { put("lastTimedOutWait", it.name) }
            launchDiagnostics?.let { put("launchDiagnostics", JSONObject()
                .put("linesRead", it.linesRead).put("packageBlockedLines", it.packageBlockedLines)
                .put("conversationBlockedLines", it.conversationBlockedLines)
                .put("balBlockMentions", it.balBlockMentions).put("fixtureActivities", it.fixtureActivities)) }
            put("firstElapsedMs", JSONObject().apply {
                first.forEach { (phase, event) -> put(phase.name, (event.nanos - started) / 1_000_000.0) }
            })
            put("droppedEvents", dropped); put("phases", phases)
            put("endpointTrace", JSONObject().apply {
                put("clock", "SystemClock.elapsedRealtimeNanos")
                put("typeEncoding", "CallEndpointCompat.type")
                put("limit", ANSWER_ENDPOINT_LIMIT); put("typeLimit", ANSWER_ENDPOINT_TYPE_LIMIT)
                put("droppedEvents", droppedEndpoints)
                val admitted = endpointAdmissionNanos
                put("admittedElapsedMs", admitted?.let { (it - started) / 1_000_000.0 } ?: JSONObject.NULL)
                put("stoppedElapsedMs", endpointStopNanos?.let { (it - started) / 1_000_000.0 } ?: JSONObject.NULL)
                put("emissions", JSONArray().apply {
                    for (event in endpoints) put(JSONObject()
                        .put("phase", event.phase.name)
                        .put("elapsedMs", (event.nanos - started) / 1_000_000.0)
                        .put("sinceAdmissionMs", (event.nanos - checkNotNull(admitted)) / 1_000_000.0)
                        .put("types", JSONArray(event.types)).put("endpointCount", event.count)
                        .put("droppedTypes", event.count - event.types.size))
                })
            })
        }.toString()
    }
}

/** Main-thread observation surface shared by the fixture Activity and its test. */
internal object AnswerJourneyObservation {
    var trace: AnswerJourneyTrace? = null
}
