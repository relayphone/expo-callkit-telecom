package expo.modules.callkittelecom.conversations

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

internal enum class AnswerJourneyPhase {
    FIXTURE_STARTED, INCOMING_REPORTED, WAIT_STARTED, WAIT_COMPLETED, WAIT_TIMED_OUT, WAIT_CANCELLED,
    HOME_SENT, PENDING_INTENT_SENT, PENDING_INTENT_RETURNED, ACTIVITY_CREATED, ACTIVITY_NEW_INTENT,
    ACTIVITY_RESUMED, TOKEN_CONSUMED_AT_RECEIVE_RETURN, NATIVE_ANSWER_STARTED,
    CORE_ANSWER_STARTED, CORE_ANSWER_RETURNED, CORE_SET_ACTIVE_STARTED, CORE_SET_ACTIVE_RETURNED,
    TELECOM_READ_STARTED, TELECOM_READ_RETURNED, TELECOM_ACTIVE, SDK_SNAPSHOT,
    SDK_SNAPSHOT_CONNECTING, SDK_SNAPSHOT_CONNECTED,
    ANSWER_PERFORMED, BODY_COMPLETED, CLEANUP_STARTED,
}

internal enum class AnswerJourneyWait {
    INCOMING_NOTIFICATION, ONGOING_NOTIFICATION, HOME_FOCUS_LOST, ACTIVITY_FOREGROUND,
    AUDIO_ROUTE, RECEIVER_ROUTE, SNAPSHOT_ANSWERED, TELECOM_ACTIVE, ANSWER_PERFORMED,
    CONTENT_OVERRIDE, ACTIVITY_RECREATED, PLATFORM_REMOVED, ACTIVITY_DESTROYED,
}

/** Bounded test observations only. Tokens receive local ordinals and are never serialized. */
internal class AnswerJourneyTrace(val conversationId: String, private val now: () -> Long) {
    private data class Event(val phase: AnswerJourneyPhase, val nanos: Long, val wait: AnswerJourneyWait?,
        val token: Int?, val tokenPresent: Boolean?, val state: String?)
    private val started = now()
    private val events = mutableListOf<Event>()
    private val tokens = mutableMapOf<String, Int>()
    private val first = linkedMapOf<AnswerJourneyPhase, Event>()
    private var dropped = 0
    private var currentToken: Int? = null
    var lastTimeout: AnswerJourneyWait? = null
        private set
    private var firstTimeout: AnswerJourneyWait? = null

    fun mark(phase: AnswerJourneyPhase, wait: AnswerJourneyWait? = null, atNanos: Long = now(),
        token: Int? = currentToken, tokenPresent: Boolean? = null, state: String? = null) {
        if (events.size == 256) { events.removeAt(64); dropped++ }
        val event = Event(phase, atNanos, wait, token, tokenPresent, state)
        events += event
        first.putIfAbsent(phase, event)
        if (phase == AnswerJourneyPhase.WAIT_TIMED_OUT) {
            if (firstTimeout == null) firstTimeout = wait
            lastTimeout = wait
        }
    }

    fun intent(phase: AnswerJourneyPhase, intent: Intent): Int? {
        if (intent.getStringExtra(ConversationNotificationIntents.EXTRA_CONVERSATION_ID) != conversationId) return null
        val token = intent.getStringExtra("expo.modules.callkittelecom.answerToken")
        val correlation = token ?: intent.data?.takeIf { it.scheme == "voip-conversation" }?.lastPathSegment
        currentToken = correlation?.let { tokens.getOrPut(it) { tokens.size + 1 } }
        mark(phase, tokenPresent = token != null)
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
            put("firstElapsedMs", JSONObject().apply {
                first.forEach { (phase, event) -> put(phase.name, (event.nanos - started) / 1_000_000.0) }
            })
            put("droppedEvents", dropped); put("phases", phases)
        }.toString()
    }
}

/** Main-thread observation surface shared by the fixture Activity and its test. */
internal object AnswerJourneyObservation {
    var trace: AnswerJourneyTrace? = null
}
