package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal enum class PlacementConnectionReport { CONNECTING, CONNECTED }
private enum class PlacementCancellation(val wireValue: String) {
    REPLACED("handlerReplaced"), CANCELLED("cancelled"), DEADLINE("deadline")
}

/** Main-confined outgoing placement; native actions never enter the application's handler. */
internal class StartPlacementCoordinator(
    private val emit: (String, Map<String, Any?>) -> Unit,
    private val changed: () -> Unit,
    private val timeout: Duration = 3500.milliseconds,
    private val monotonicNow: () -> Long = SystemClock::elapsedRealtime,
    private val wallNow: () -> Instant = Instant::now,
) {
    private class Entry(val conversationId: String, val body: Map<String, Any?>, val expires: Long) {
        val result = CompletableDeferred<Boolean>()
        var delivered: Int? = null
        var report: PlacementConnectionReport? = null
    }
    private val entries = linkedMapOf<String, Entry>()
    private var generation = 0
    private var attached = false
    var epoch = 0L
        private set
    var acceptUnhandled = false

    init { require(timeout.inWholeMilliseconds in 1L..3500L) }

    fun resetBridge() {
        epoch++; generation = 0; attached = false
        withdrawDeliveries()
    }

    fun attach(value: Boolean, nextGeneration: Int, ownerEpoch: Long) {
        if (ownerEpoch != epoch || nextGeneration <= generation) return
        generation = nextGeneration; attached = value
        withdrawDeliveries()
        if (epoch == ownerEpoch && generation == nextGeneration && attached)
            entries.toList().forEach { (id, entry) -> deliver(id, entry) }
    }

    private fun withdrawDeliveries() {
        val previous = entries.mapNotNull { (id, entry) -> entry.delivered?.let { id to it } }
        entries.values.forEach { it.delivered = null }
        previous.forEach { (id, owner) -> cancelEvent(id, owner, PlacementCancellation.REPLACED) }
    }

    fun settle(id: String, owner: Int, accepted: Boolean, ownerEpoch: Long) {
        val entry = entries[id] ?: return
        if (ownerEpoch == epoch && owner == generation && entry.delivered == owner && monotonicNow() < entry.expires)
            entry.result.complete(accepted)
    }

    fun pending(conversationId: String): Map<String, Any?>? = entries.entries.firstOrNull {
        it.value.conversationId == conversationId
    }?.let { (id, entry) -> entry.body.filterKeys { it != "conversation" && it != "actionID" } + ("id" to id) }

    /** A successful placement applies the latest transport fact; failure discards it. */
    fun deferReport(conversationId: String, report: PlacementConnectionReport): Boolean {
        val entry = entries.values.firstOrNull { it.conversationId == conversationId } ?: return false
        if (entry.report != PlacementConnectionReport.CONNECTED) entry.report = report
        return true
    }

    fun cancel(conversationId: String) {
        entries.toList().filter { it.second.conversationId == conversationId }.forEach { (id, entry) ->
            val owner = entry.delivered
            entry.delivered = null
            entry.result.complete(false)
            owner?.let { cancelEvent(id, it, PlacementCancellation.CANCELLED) }
        }
    }

    suspend fun place(conversationId: String, conversation: Map<String, Any?>,
                      origin: NativeActionOrigin = NativeActionOrigin.APP): PlacementConnectionReport? {
        if (conversation["id"] != conversationId || entries.values.any { it.conversationId == conversationId })
            throw ConversationFailure("invalid_arguments")
        if (!attached && origin == NativeActionOrigin.APP) {
            if (!acceptUnhandled) throw ConversationFailure("handler_failed")
            return null
        }
        val id = UUID.randomUUID().toString()
        val entry = Entry(conversationId, mapOf("actionID" to id, "conversation" to conversation.toMap(),
            "kind" to "start", "origin" to origin.wireValue,
            "deadline" to wallNow().plusMillis(timeout.inWholeMilliseconds).toString()),
            monotonicNow() + timeout.inWholeMilliseconds)
        entries[id] = entry
        var cancellation = PlacementCancellation.DEADLINE
        try {
            changed()
            deliver(id, entry)
            val result = withTimeoutOrNull(timeout.inWholeMilliseconds) { entry.result.await() }
            if (result == null && !(entry.delivered == null && acceptUnhandled)) throw ConversationFailure("deadline_exceeded")
            if (result == false) throw ConversationFailure("handler_failed")
            currentCoroutineContext().ensureActive()
            return entry.report
        } catch (error: CancellationException) {
            cancellation = PlacementCancellation.CANCELLED
            throw error
        } finally {
            entries.remove(id)
            if (!entry.result.isCompleted) entry.delivered?.let { cancelEvent(id, it, cancellation) }
            changed()
        }
    }

    private fun deliver(id: String, entry: Entry) {
        if (!attached || entries[id] !== entry || entry.result.isCompleted ||
            entry.delivered == generation || monotonicNow() >= entry.expires) return
        entry.delivered = generation
        emit("handlerAction", entry.body + ("generation" to generation))
    }

    private fun cancelEvent(id: String, owner: Int, reason: PlacementCancellation) = emit("actionCancelled",
        mapOf("actionID" to id, "generation" to owner, "reason" to reason.wireValue))
}
