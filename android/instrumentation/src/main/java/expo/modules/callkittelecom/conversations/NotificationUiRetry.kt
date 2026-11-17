package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.delay

/** Navigation retries consume the caller's existing five-second wait, never an assertion retry. */
internal class NotificationUiRetry(private val nowMillis: () -> Long) {
    private val started = nowMillis()
    private var retried = false
    fun onceAfter(millis: Long, retry: () -> Unit) {
        if (!retried && nowMillis() - started in millis until 5000) {
            retried = true
            retry()
        }
    }
    suspend fun home(pressHome: () -> Boolean, launcherVisible: () -> Boolean, launchHome: () -> Unit,
        pause: suspend () -> Unit = { delay(10) }) {
        val pressed = pressHome()
        while (!launcherVisible()) {
            onceAfter(if (pressed) 1000 else 0, launchHome)
            pause()
        }
    }
}
