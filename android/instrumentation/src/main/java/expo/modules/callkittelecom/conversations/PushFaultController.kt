package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal data class PushFaultDelivery(val deliveryId: String, val callId: String,
    val eventId: String, val delayMillis: Long = 0, val drop: Boolean = false) {
    init {
        require(deliveryId.isNotBlank() && deliveryId.length <= 128)
        require(callId.isNotBlank() && callId.length <= 256)
        require(eventId.isNotBlank() && eventId.length <= 512)
        require(delayMillis in 0..30_000)
    }
}

internal enum class PushFaultPhase(val value: String) {
    SCHEDULED("scheduled"), DROPPED("dropped"), RECOGNIZED("recognized"),
    UNRECOGNIZED("unrecognized"), FAILED("failed"), CANCELLED("cancelled")
}

internal sealed interface PushFaultEvent {
    data class Delivery(val request: PushFaultDelivery, val phase: PushFaultPhase,
        val elapsedRealtimeNanos: Long) : PushFaultEvent
    data class TokenRebind(val deliveryId: String, val absent: Boolean,
        val tokenPresent: Boolean, val tokenEmpty: Boolean, val revisionBefore: Long,
        val revisionAfter: Long, val sameSnapshot: Boolean, val elapsedRealtimeNanos: Long) : PushFaultEvent
}

/** Main-confined, bounded fault scheduling; only non-dropped deliveries reach the real intake. */
internal class PushFaultController(private val scope: CoroutineScope,
    private val nowNanos: () -> Long, private val record: (PushFaultEvent) -> Unit,
    private val deliver: (PushFaultDelivery) -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private val seen = mutableSetOf<String>()
    private val pending = mutableMapOf<String, Job>()

    fun enqueue(request: PushFaultDelivery): Job {
        check(scope.isActive) { "Push fixture scope is closed" }
        require(request.deliveryId !in seen) { "Delivery ID already used" }
        check(seen.size < 64) { "Reset after 64 deliveries" }
        seen += request.deliveryId
        record(PushFaultEvent.Delivery(request, PushFaultPhase.SCHEDULED, nowNanos()))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                pause(request.delayMillis)
                val phase = if (request.drop) PushFaultPhase.DROPPED
                    else if (deliver(request)) PushFaultPhase.RECOGNIZED else PushFaultPhase.UNRECOGNIZED
                record(PushFaultEvent.Delivery(request, phase, nowNanos()))
            } catch (error: CancellationException) {
                record(PushFaultEvent.Delivery(request, PushFaultPhase.CANCELLED, nowNanos()))
                throw error
            } catch (_: Exception) {
                record(PushFaultEvent.Delivery(request, PushFaultPhase.FAILED, nowNanos()))
            } finally { pending.remove(request.deliveryId) }
        }
        pending[request.deliveryId] = job
        job.start()
        return job
    }

    fun reset() { check(pending.isEmpty()) { "Deliveries are pending" }; seen.clear() }
    suspend fun cancelPending() { pending.values.toList().forEach { it.cancelAndJoin() } }
}

/** Only fixture identities, times and token-presence bits are serialized, never token values. */
internal class PushFaultLog(private val processId: Int, private val write: (String) -> Unit) {
    private val events = mutableListOf<PushFaultEvent>()
    fun clear() { events.clear(); flush() }
    fun record(event: PushFaultEvent) {
        check(events.size < 256) { "Reset the bounded push log" }
        events += event
        flush()
    }
    private fun flush() {
        val records = events.map { event -> when (event) {
            is PushFaultEvent.Delivery -> JSONObject().put("kind", "delivery")
                .put("deliveryId", event.request.deliveryId).put("callId", event.request.callId)
                .put("eventId", event.request.eventId).put("delayMillis", event.request.delayMillis)
                .put("phase", event.phase.value).put("elapsedRealtimeNanos", event.elapsedRealtimeNanos.toString())
            is PushFaultEvent.TokenRebind -> JSONObject().put("kind", "tokenRebind")
                .put("deliveryId", event.deliveryId).put("tokenState", if (event.absent) "absent" else "empty")
                .put("tokenPresent", event.tokenPresent).put("tokenEmpty", event.tokenEmpty)
                .put("revisionBefore", event.revisionBefore).put("revisionAfter", event.revisionAfter)
                .put("sameSnapshot", event.sameSnapshot).put("elapsedRealtimeNanos", event.elapsedRealtimeNanos.toString())
        } }
        write(JSONObject().put("schemaVersion", 1).put("processId", processId)
            .put("events", JSONArray(records)).toString())
    }
}
