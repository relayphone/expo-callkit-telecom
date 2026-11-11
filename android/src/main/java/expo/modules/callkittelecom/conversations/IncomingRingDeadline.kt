package expo.modules.callkittelecom.conversations

import java.time.Clock
import kotlinx.coroutines.delay

/** Converts a push's absolute expiry at admission time; absent expiry keeps the configured timeout. */
internal class IncomingRingDeadline(
    private val clock: Clock = Clock.systemUTC(),
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun await(expiresAtMillis: Long?, fallbackMillis: Long) {
        val remaining = if (expiresAtMillis == null) fallbackMillis else {
            val now = clock.millis()
            if (expiresAtMillis <= now) 0L
            else (expiresAtMillis - now).takeIf { it >= 0 } ?: Long.MAX_VALUE
        }
        waitMillis(remaining)
    }
}
