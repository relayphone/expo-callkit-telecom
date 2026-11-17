package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/** Latest presentation per notification; lifecycle posts establish the next refresh interval. */
internal class NotificationRefreshSchedule<T : Any>(
    private val sameDisplay: (T, T) -> Boolean,
    minimumInterval: Duration = 1100.milliseconds,
) {
    private data class Slot<T>(val posted: T?, val postedAtNanos: Long?, var pending: T?)
    private val slots = mutableMapOf<Int, Slot<T>>()
    private val minimumNanos = minimumInterval.inWholeNanoseconds

    init { require(minimumInterval.isFinite() && minimumInterval > Duration.ZERO) }

    fun request(id: Int, value: T): Boolean {
        val slot = slots.getOrPut(id) { Slot(null, null, null) }
        slot.pending = value.takeUnless { slot.posted?.let { posted -> sameDisplay(posted, value) } == true }
        return slot.pending != null
    }

    fun pending(id: Int): T? = slots[id]?.pending

    fun delay(id: Int, nowNanos: Long): Duration {
        val postedAt = slots[id]?.postedAtNanos ?: return Duration.ZERO
        return (minimumNanos - (nowNanos - postedAt)).coerceAtLeast(0).nanoseconds
    }

    fun posted(id: Int, value: T, nowNanos: Long) {
        slots[id] = Slot(value, nowNanos, null)
    }

    fun remove(id: Int) { slots.remove(id) }
    fun clear() { slots.clear() }
}

/** Cancelling an immediate coroutine can run its map-removing finally block inline. */
internal fun cancelNotificationRefreshJobs(jobs: MutableMap<Int, Job>) {
    val pending = jobs.values.toList()
    jobs.clear()
    pending.forEach { it.cancel() }
}
