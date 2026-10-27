package expo.modules.callkittelecom.conversations

import android.media.AudioManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Core activation already owns focus; endpoint metadata is not another activation acknowledgment. */
internal object NativeAudioAuthority {
    suspend fun await(isCurrentActive: () -> Boolean, mode: () -> Int) = withTimeout(1500) {
        while (true) {
            check(isCurrentActive()) { "Native call is no longer active" }
            if (mode() == AudioManager.MODE_IN_COMMUNICATION) break
            delay(10)
        }
        check(isCurrentActive()) { "Native call is no longer active" }
    }
}
