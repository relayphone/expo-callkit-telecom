package expo.modules.callkittelecom.conversations

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import android.os.SystemClock
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Each caller owns the existing 5000 ms wait; UiAutomator adds no nested idle/selector wait. */
private suspend fun <T> systemUi(body: suspend (UiDevice) -> T): T = withContext(Dispatchers.IO) {
    val configuration = Configurator.getInstance()
    val idle = configuration.waitForIdleTimeout
    val selector = configuration.waitForSelectorTimeout
    try {
        configuration.waitForIdleTimeout = 0
        configuration.waitForSelectorTimeout = 0
        body(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
    } finally {
        configuration.waitForIdleTimeout = idle
        configuration.waitForSelectorTimeout = selector
    }
}

internal suspend fun openNotificationTestShade() = systemUi { device ->
    withContext(Dispatchers.Main) { AnswerJourneyObservation.trace?.mark(AnswerJourneyPhase.NOTIFICATION_SHADE_OPEN_STARTED) }
    check(device.openNotification()) { "Could not open the notification shade" }
    val own = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    while (true) {
        val focused = withContext(Dispatchers.Main) {
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().any { stage -> lifecycle.getActivitiesInStage(stage).any {
                it.packageName == own && !it.isDestroyed && it.hasWindowFocus()
            } }
        }
        if (!focused && device.currentPackageName == "com.android.systemui") break
        delay(10)
    }
    withContext(Dispatchers.Main) { AnswerJourneyObservation.trace?.mark(AnswerJourneyPhase.NOTIFICATION_SHADE_OPEN_COMPLETED) }
}

/** Select the posted person before selecting an action, so another call cannot satisfy this tap. */
internal suspend fun tapNotificationTestCard(person: String, actionTitle: String? = null) {
    openNotificationTestShade()
    systemUi { device ->
        var expanded = false
        while (true) {
            val label = device.findObject(By.pkg("com.android.systemui").text(person))
            if (label != null) {
                var row: UiObject2? = label
                while (row != null && row.resourceName != "com.android.systemui:id/expandableNotificationRow") row = row.parent
                if (row != null) {
                    val title = actionTitle?.let { Pattern.compile(Pattern.quote(it), Pattern.CASE_INSENSITIVE) }
                    val target = if (title == null) label else row.findObject(By.text(title)) ?: row.findObject(By.desc(title))
                    if (target != null) {
                        val tapped = SystemClock.elapsedRealtimeNanos()
                        target.click()
                        withContext(Dispatchers.Main) { AnswerJourneyObservation.trace?.mark(
                            AnswerJourneyPhase.SYSTEMUI_ACTION_TAPPED, atNanos = tapped) }
                        return@systemUi
                    }
                    if (!expanded) {
                        row.findObject(By.res("android", "expand_button"))?.let { it.click(); expanded = true }
                    }
                }
            }
            delay(10)
        }
    }
}

/** Real Home navigation is used only by presentation scenarios which require a background app. */
internal suspend fun backgroundNotificationTestApp() = systemUi { device ->
    check(device.pressHome()) { "Could not navigate Home" }
    val own = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    while (device.currentPackageName.let { it == null || it == own || it == "com.android.systemui" }) delay(10)
}
