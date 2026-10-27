package expo.modules.callkittelecom.conversations

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAudioAuthorityTest {
    @Test fun activeCoreCallWaitsForCommunicationModeWithoutAnEndpointAcknowledgment() = runBlocking {
        var mode = AudioManager.MODE_RINGTONE
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            NativeAudioAuthority.await({ true }, { mode })
        }
        delay(30)
        assertFalse(ready.isCompleted)
        mode = AudioManager.MODE_IN_COMMUNICATION
        withTimeout(1000) { ready.await() }
    }

    @Test fun anInactiveOrReplacedCallCannotUseAnotherCallsCommunicationMode() = runBlocking {
        val error = runCatching {
            NativeAudioAuthority.await({ false }, { AudioManager.MODE_IN_COMMUNICATION })
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test fun losingTheCallWhileWaitingCannotAuthorizeCapture() = runBlocking {
        var current = true
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { NativeAudioAuthority.await({ current }, { AudioManager.MODE_NORMAL }) }
        }
        delay(30); current = false
        assertTrue(withTimeout(1000) { ready.await() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun communicationModeKeepsTheUnchangedDeadline() = runBlocking {
        val started = android.os.SystemClock.elapsedRealtime()
        val error = runCatching {
            NativeAudioAuthority.await({ true }, { AudioManager.MODE_NORMAL })
        }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertTrue(android.os.SystemClock.elapsedRealtime() - started >= 1400)
    }
}
