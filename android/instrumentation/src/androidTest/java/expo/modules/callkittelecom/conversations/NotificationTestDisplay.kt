package expo.modules.callkittelecom.conversations

import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Establish an awake, unlocked display before UI assertions. Caller bounds the wait. */
internal suspend fun prepareNotificationTestDisplay() = withContext(Dispatchers.Main) {
    AnswerJourneyObservation.trace?.mark(AnswerJourneyPhase.DISPLAY_WAKE_STARTED)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    withContext(Dispatchers.IO) {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            val result = instrumentation.uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
        }
    }
    val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
    val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
    while (!power.isInteractive || keyguard.isKeyguardLocked) delay(10)
    AnswerJourneyObservation.trace?.mark(AnswerJourneyPhase.DISPLAY_READY)
}
