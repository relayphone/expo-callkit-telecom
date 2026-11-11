package expo.modules.callkittelecom.conversations

import java.time.Clock
import kotlinx.coroutines.delay

/** A push's absolute expiry can shorten the configured timeout at admission, never extend it. */
internal class IncomingRingDeadline(
    private val clock: Clock = Clock.systemUTC(),
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun await(expiresAtMillis: Long?, fallbackMillis: Long) {
        val remaining = if (expiresAtMillis == null) fallbackMillis else {
            val now = clock.millis()
            if (expiresAtMillis <= now) 0L
            else minOf(fallbackMillis, (expiresAtMillis - now).takeIf { it >= 0 } ?: fallbackMillis)
        }
        waitMillis(remaining)
    }
}
