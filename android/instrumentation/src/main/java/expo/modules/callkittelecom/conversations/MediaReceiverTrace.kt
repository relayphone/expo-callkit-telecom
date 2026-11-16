package expo.modules.callkittelecom.conversations

import org.json.JSONArray
import org.json.JSONObject

internal enum class MediaReceiverHttpPhase { CREATED, ENQUEUED, HEADERS, BODY, PARSED }

/** Bounded receiver polls only; request URLs, bodies and exception messages are never retained. */
internal class MediaReceiverTrace(private val nanoTime: () -> Long) {
    private val lock = Any()
    private data class Poll(val startedNs: Long, var endedNs: Long? = null,
        var phase: MediaReceiverHttpPhase = MediaReceiverHttpPhase.CREATED, var errorClass: String? = null)
    private val polls = ArrayDeque<Poll>()
    private var reads = 0
    private var startedNs: Long? = null
    private var prejoinedNs: Long? = null
    private var completedNs: Long? = null
    private var failureNs: Long? = null
    private var budgetMs = 10_000L
    private var last = JSONObject()

    fun started(budget: Long) = synchronized(lock) { budgetMs = budget; startedNs = nanoTime() }
    fun prejoined() = synchronized(lock) { prejoinedNs = nanoTime() }
    fun completed() = synchronized(lock) { completedNs = nanoTime() }
    fun failed() = synchronized(lock) { failureNs = nanoTime() }

    suspend fun read(block: suspend ((MediaReceiverHttpPhase) -> Unit) -> JSONObject): JSONObject {
        val poll = synchronized(lock) {
            reads++
            if (polls.size == 32) polls.removeFirst()
            Poll(nanoTime()).also { polls.addLast(it) }
        }
        try {
            return block { phase -> synchronized(lock) {
                if (poll.endedNs == null) poll.phase = phase
            } }.also { peer -> synchronized(lock) { last = safeReceiver(peer) } }
        } catch (error: Throwable) {
            synchronized(lock) { poll.errorClass = mediaErrorClass(error.javaClass.simpleName) }
            throw error
        } finally { synchronized(lock) { poll.endedNs = nanoTime() } }
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        val latest = polls.lastOrNull()
        JSONObject().put("clock", "android.elapsedRealtimeNanos").put("budgetMs", budgetMs)
            .put("startedNs", startedNs?.toString()).put("prejoinedObservedNs", prejoinedNs?.toString())
            .put("completedNs", completedNs?.toString()).put("failureObservedNs", failureNs?.toString())
            .put("reads", reads).put("droppedPolls", reads - polls.size)
            .put("lastReadStartedNs", latest?.startedNs?.toString()).put("lastReadCompletedNs", latest?.endedNs?.toString())
            .put("lastRunning", last.opt("running")).put("last", JSONObject(last.toString()))
            .put("polls", JSONArray(polls.map { poll -> JSONObject()
                .put("startedNs", poll.startedNs.toString()).put("endedNs", poll.endedNs?.toString() ?: JSONObject.NULL)
                .put("phase", poll.phase.name).put("errorClass", poll.errorClass ?: JSONObject.NULL) }))
    }
}

internal fun mediaErrorClass(value: String?): String = value?.takeIf {
    it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}(Error|Exception)"))
} ?: "UnclassifiedError"

/** Copy only typed receiver state and counters, never marker IDs, PCM, RTC payloads or messages. */
internal fun safeReceiver(peer: JSONObject): JSONObject = JSONObject().apply {
    (peer.opt("running") as? Boolean)?.let { put("running", it) }
    for (key in listOf("generation", "frames", "markers", "markersWithPostRoll", "cleanCyclesWithPostRoll")) {
        val value = peer.opt(key)
        if ((value is Int || value is Long) && (value as Number).toLong() >= 0) put(key, value)
    }
    if (peer.has("error")) put("errorClass", if (peer.isNull("error")) JSONObject.NULL else mediaErrorClass(peer.opt("error") as? String))
}
