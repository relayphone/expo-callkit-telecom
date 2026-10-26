package expo.modules.callkittelecom.conversations

import android.os.SystemClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Main-dispatcher confined. Core-Telecom allows 5s; reserve 1.5s for settlement. */
class ActionCoordinator(private val emit: (String, Map<String, Any?>) -> Unit,
                        private val changed: () -> Unit) {
    private data class Entry(val body: Map<String, Any?>, val expires: Long,
                             val result: CompletableDeferred<Boolean> = CompletableDeferred(),
                             var delivered: Int? = null)
    private val entries = linkedMapOf<String, Entry>()
    var epoch = 0L
        private set
    private var generation = 0
    private var attached = false
    var acceptUnhandled = false
    var coldJoinBudgetMs = 3500L
        set(value) { require(value > 0); field = value.coerceAtMost(3500) }

    fun resetBridge() {
        epoch++
        entries.values.forEach { entry -> entry.delivered?.let {
            cancelEvent(entry.body["actionID"] as String, it, "handlerReplaced")
        }; entry.delivered = null }
        generation = 0
        attached = false
    }
    fun attach(value: Boolean, nextGeneration: Int, ownerEpoch: Long) {
        if (ownerEpoch != epoch) return
        if (nextGeneration <= generation) return
        for ((id, entry) in entries) {
            entry.delivered?.let { cancelEvent(id, it, "handlerReplaced") }
            entry.delivered = null
        }
        generation = nextGeneration
        attached = value
        if (attached) entries.values.toList().forEach(::deliver)
    }

    fun settle(id: String, owner: Int, accepted: Boolean, ownerEpoch: Long) {
        if (ownerEpoch != epoch) return
        val entry = entries[id] ?: return
        if (entry.delivered == owner && owner == generation &&
            SystemClock.elapsedRealtime() < entry.expires) entry.result.complete(accepted)
    }

    fun pending(conversationId: String): Map<String, Any?>? = entries.values.firstOrNull {
        (it.body["conversation"] as? Map<*, *>)?.get("id") == conversationId
    }?.body?.let { body -> body.filterKeys { it != "conversation" && it != "actionID" } + ("id" to body["actionID"]) }

    fun cancel(conversationId: String) {
        entries.values.filter { (it.body["conversation"] as? Map<*, *>)?.get("id") == conversationId }
            .forEach { entry ->
                entry.delivered?.let { cancelEvent(entry.body["actionID"] as String, it, "cancelled") }
                entry.result.complete(false)
            }
    }

    suspend fun perform(conversation: Map<String, Any?>, kind: String, origin: String,
                        arguments: Map<String, Any?> = emptyMap()) {
        val budget = if (kind == "join" && !attached) coldJoinBudgetMs else 3500L
        val id = UUID.randomUUID().toString()
        val entry = Entry(mapOf("actionID" to id, "conversation" to conversation, "kind" to kind,
            "origin" to origin, "deadline" to Instant.now().plusMillis(budget).toString()) + arguments,
            SystemClock.elapsedRealtime() + budget)
        entries[id] = entry
        changed()
        if (attached) deliver(entry)
        try {
            val result = withTimeoutOrNull(budget) { entry.result.await() }
            if (result == null && !(entry.delivered == null && acceptUnhandled)) throw ConversationFailure("deadline_exceeded")
            if (result == false) throw ConversationFailure("handler_failed")
        } finally {
            entries.remove(id)
            if (!entry.result.isCompleted) entry.delivered?.let { cancelEvent(id, it, "deadline") }
            changed()
        }
    }

    private fun deliver(entry: Entry) {
        if (entry.result.isCompleted || SystemClock.elapsedRealtime() >= entry.expires) return
        entry.delivered = generation
        emit("handlerAction", entry.body + ("generation" to generation))
    }
    private fun cancelEvent(id: String, owner: Int, reason: String) = emit("actionCancelled",
        mapOf("actionID" to id, "generation" to owner, "reason" to reason))
}
