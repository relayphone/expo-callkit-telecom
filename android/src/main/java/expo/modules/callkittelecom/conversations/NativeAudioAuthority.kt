package expo.modules.callkittelecom.conversations

import android.media.AudioManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Core activation already owns focus; endpoint metadata is not another activation acknowledgment. */
internal object NativeAudioAuthority {
    suspend fun await(isCurrentActive: () -> Boolean, mode: () -> Int, timedOut: () -> Unit = {}) {
        try {
            withTimeout(1500) {
                while (true) {
                    check(isCurrentActive()) { "Native call is no longer active" }
                    if (mode() == AudioManager.MODE_IN_COMMUNICATION) break
                    delay(10)
                }
                check(isCurrentActive()) { "Native call is no longer active" }
            }
        } catch (error: TimeoutCancellationException) {
            // A caller's cancellation or shorter deadline is not an audio-authority timeout.
            currentCoroutineContext().ensureActive()
            timedOut()
            throw error
        }
    }
}
