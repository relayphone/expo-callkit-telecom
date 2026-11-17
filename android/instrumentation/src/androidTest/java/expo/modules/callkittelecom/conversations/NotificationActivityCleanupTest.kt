package expo.modules.callkittelecom.conversations

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationActivityCleanupTest {
    @Test fun notificationShadeKeepsActivitiesAliveAndCanBeRepeated() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activities = mutableListOf<Activity>()
        val previousTrace = AnswerJourneyObservation.trace
        var wait = AnswerJourneyWait.DISPLAY_ASLEEP
        try {
            val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
            withContext(Dispatchers.IO) {
                val command = instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP")
                ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
            }
            withTimeout(5000) { while (power.isInteractive) delay(10) }
            wait = AnswerJourneyWait.DISPLAY_READY
            withTimeout(5000) { prepareNotificationTestDisplay() }
            assertTrue("Foreground fixtures require an interactive display", power.isInteractive)
            assertFalse("Foreground fixtures require a dismissed keyguard", instrumentation.targetContext
                .getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            wait = AnswerJourneyWait.APP_BACKGROUND
            withTimeout(5000) { backgroundNotificationTestApp() }
            for (type in listOf(NotificationLaunchActivity::class.java, NotificationAlternateActivity::class.java)) {
                activities += withContext(Dispatchers.IO) {
                    instrumentation.startActivitySync(Intent(instrumentation.targetContext, type)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
            }
            withContext(Dispatchers.Main) {
                wait = AnswerJourneyWait.ACTIVITY_FOREGROUND
                withTimeout(5000) { while (!activities.last().hasWindowFocus()) delay(10) }
                assertEquals(2, activities.map { it.taskId }.distinct().size)
                val trace = AnswerJourneyTrace("notification-shade", SystemClock::elapsedRealtimeNanos)
                AnswerJourneyObservation.trace = trace
                repeat(2) {
                    wait = AnswerJourneyWait.SYSTEMUI_ACTION
                    withTimeout(5000) { openNotificationTestShade() }
                    assertTrue("Shade-covered fixture Activities must stay alive and lose focus",
                        activities.none { it.hasWindowFocus() || it.isDestroyed })
                    val foreground = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                    assertNotNull(foreground)
                    assertNotEquals(instrumentation.targetContext.packageName, foreground)
                }
                val phases = JSONObject(trace.json("notification-shade", true)).getJSONArray("phases")
                val backgroundPhases = (0 until phases.length()).map { phases.getJSONObject(it).getString("phase") }
                    .filter { it == "NOTIFICATION_SHADE_OPEN_STARTED" || it == "NOTIFICATION_SHADE_OPEN_COMPLETED" }
                assertEquals(List(2) { listOf("NOTIFICATION_SHADE_OPEN_STARTED", "NOTIFICATION_SHADE_OPEN_COMPLETED") }.flatten(),
                    backgroundPhases)
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Notification shade fixture wait ${wait.name} timed out", error)
        } finally {
            withContext(Dispatchers.Main) {
                AnswerJourneyObservation.trace = previousTrace
                activities.forEach { it.finish() }
                withTimeout(5000) { while (activities.any { !it.isDestroyed }) delay(10) }
            }
        }
    }

    @Test fun aFailedBodyClosesEveryFixtureActivityIncludingThePreviousTask() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activities = mutableListOf<Activity>()
        try {
            for (type in listOf(NotificationLaunchActivity::class.java, NotificationAlternateActivity::class.java)) {
                activities += withContext(Dispatchers.IO) {
                    instrumentation.startActivitySync(Intent(instrumentation.targetContext, type)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
            }
            withContext(Dispatchers.Main) {
                val failure = AssertionError("Deliberate fixture body failure")
                val observed = runCatching { withPresentationCleanup({ throw failure }, {
                    NotificationLaunchActivity.finishAll()
                    withTimeout(5000) { while (NotificationLaunchActivity.hasActivities) delay(10) }
                }) }.exceptionOrNull()
                assertSame("Cleanup must preserve the original failure", failure, observed)
                assertTrue("Every fixture Activity must be destroyed", activities.all { it.isDestroyed })
                assertFalse(NotificationLaunchActivity.hasActivities)
            }
        } finally {
            withContext(Dispatchers.Main) {
                activities.forEach { it.finish() }
                withTimeout(5000) { while (activities.any { !it.isDestroyed }) delay(10) }
            }
        }
    }
}
