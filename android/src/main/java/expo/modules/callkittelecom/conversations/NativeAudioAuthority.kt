package expo.modules.callkittelecom.conversations

import android.media.AudioManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException

/** An admitted dialing or active call must also observe communication mode before using audio. */
internal object NativeAudioAuthority {
    suspend fun await(isAudioEligible: () -> Boolean, mode: () -> Int, timedOut: () -> Unit = {}) {
        try {
            withNativeMediaDeadline(1500) {
                while (true) {
                    check(isAudioEligible()) { "Native call no longer owns audio" }
                    if (mode() == AudioManager.MODE_IN_COMMUNICATION) break
                    delay(10)
                }
                check(isAudioEligible()) { "Native call no longer owns audio" }
            }
        } catch (error: TimeoutCancellationException) {
            // A caller's cancellation or shorter deadline is not an audio-authority timeout.
            currentCoroutineContext().ensureActive()
            timedOut()
            throw error
        }
    }
}
