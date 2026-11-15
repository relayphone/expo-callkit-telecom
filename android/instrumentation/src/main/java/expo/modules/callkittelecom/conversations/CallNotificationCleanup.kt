package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.delay

/** Notification identities only; notification text, intents and extras are never retained. */
internal data class CallNotificationState(
    val key: String, val id: Int, val tag: String?, val channel: String,
    val flags: Int, val ongoing: Boolean, val clearable: Boolean,
)

internal data class CallNotificationSample(
    val phase: String, val elapsedNanos: Long, val notifications: List<CallNotificationState>,
)

/** Test-host observations of asynchronous platform removal, bounded independently by the caller. */
internal class CallNotificationCleanup(
    private val read: () -> List<CallNotificationState>,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val history = ArrayDeque<CallNotificationSample>()
    val samples: List<CallNotificationSample> get() = history.toList()

    fun sample(phase: String): List<CallNotificationState> {
        val current = read().toList()
        if (history.lastOrNull()?.let { it.phase == phase && it.notifications == current } != true) {
            if (history.size == 128) history.removeFirst()
            history.addLast(CallNotificationSample(phase, nanoTime(), current))
        }
        return current
    }

    suspend fun awaitEmpty(pause: suspend () -> Unit = { delay(25) }) {
        while (sample("awaiting-removal").isNotEmpty()) pause()
    }
}
