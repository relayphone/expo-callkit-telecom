package expo.modules.callkittelecom.conversations

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Direction
import android.os.SystemClock
import java.util.regex.Pattern
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException

/** Each caller owns the existing 5000 ms wait; UiAutomator adds no nested idle/selector wait. */
private suspend fun <T> systemUi(body: suspend (UiDevice) -> T): T = try { withContext(Dispatchers.IO) {
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
} } catch (error: TimeoutCancellationException) {
    withContext(NonCancellable + Dispatchers.IO) { captureNotificationUiFailure() }
    throw error
}

/** CI runs these fixtures with synthetic call identities; retain the actual failing UI before cleanup. */
private val notificationUiCaptured = AtomicBoolean()
internal class NotificationUiArtifactsRule : TestWatcher() {
    override fun starting(description: Description) { notificationUiCaptured.set(false) }
}

internal fun notificationUiArtifactDirectory(): File {
    val additional = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }
    val root = additional?.let(::File)
        ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
    return File(root, "systemui-failures")
}

internal fun captureNotificationUiFailure() { runCatching {
    if (!notificationUiCaptured.compareAndSet(false, true)) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = notificationUiArtifactDirectory().apply { mkdirs() }
    val prefix = "timeout-${SystemClock.uptimeMillis()}"
    val device = UiDevice.getInstance(instrumentation)
    val configuration = Configurator.getInstance(); val idle = configuration.waitForIdleTimeout
    try {
        configuration.waitForIdleTimeout = 0
        runCatching { device.dumpWindowHierarchy(File(directory, "$prefix.xml")) }
        runCatching { check(device.takeScreenshot(File(directory, "$prefix.png"))) }
        instrumentation.sendStatus(0, Bundle().apply { putString("systemUiFailureArtifacts", prefix) })
    } finally { configuration.waitForIdleTimeout = idle }
} }

internal suspend fun openNotificationTestShade() = systemUi { device ->
    openShade(device, NotificationUiRetry(SystemClock::uptimeMillis))
}

private suspend fun openShade(device: UiDevice, retry: NotificationUiRetry) {
    withContext(Dispatchers.Main) { AnswerJourneyObservation.trace?.mark(AnswerJourneyPhase.NOTIFICATION_SHADE_OPEN_STARTED) }
    device.openNotification()
    val own = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    while (true) {
        val focused = withContext(Dispatchers.Main) {
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().any { stage -> lifecycle.getActivitiesInStage(stage).any {
                it.packageName == own && !it.isDestroyed && it.hasWindowFocus()
            } }
        }
        if (!focused && device.currentPackageName == "com.android.systemui") break
        retry.onceAfter(1500) { device.openNotification() }
        delay(10)
    }
    withContext(Dispatchers.Main) { AnswerJourneyObservation.trace?.mark(AnswerJourneyPhase.NOTIFICATION_SHADE_OPEN_COMPLETED) }
}

private fun UiDevice.notificationShadeIsExpanded(): Boolean =
    hasObject(By.res("com.android.systemui", "quick_qs_panel"))

internal suspend fun collapseNotificationTestShade() = systemUi { device ->
    collapseShade(device)
}

private suspend fun collapseShade(device: UiDevice) {
    val retry = NotificationUiRetry(SystemClock::uptimeMillis)
    val dismissed = InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
        AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    while (device.notificationShadeIsExpanded()) {
        retry.onceAfter(if (dismissed) 1000 else 0) { device.pressBack() }
        delay(10)
    }
    withContext(Dispatchers.Main) { AnswerJourneyObservation.trace?.mark(AnswerJourneyPhase.SHADE_COLLAPSED) }
}

/** Select the posted person before selecting an action, so another call cannot satisfy this tap. */
internal suspend fun tapNotificationTestCard(person: String, actionTitle: String? = null) {
    systemUi { device ->
        val retry = NotificationUiRetry(SystemClock::uptimeMillis)
        openShade(device, retry)
        var expanded = false
        var scrolled = false
        while (true) {
            val match = device.findObjects(By.pkg("com.android.systemui").text(person)).mapNotNull { label ->
                var row: UiObject2? = label
                while (row != null && row.resourceName != "com.android.systemui:id/expandableNotificationRow") row = row.parent
                row?.let { it to label }
            }.distinctBy { it.first.visibleBounds }.singleOrNull()
            if (match != null) {
                val (row, label) = match
                val expand = row.findObject(By.res("android", "expand_button"))
                        ?: row.findObject(By.res("com.android.systemui", "expand_button"))
                // Qualification uses en-US emulator images; their expand descriptions are English.
                if (!expanded && expand != null &&
                        (expand.contentDescription?.startsWith("Expand", ignoreCase = true) == true ||
                            expand.contentDescription.isNullOrBlank() && actionTitle != null &&
                                row.findObject(By.res("android", "actions")) == null)) {
                        expand.click(); expanded = true; delay(10); continue
                    }
                    val title = actionTitle?.let { Pattern.compile(Pattern.quote(it), Pattern.CASE_INSENSITIVE) }
                    val target = if (title == null) label else row.findObject(By.text(title)) ?: row.findObject(By.desc(title))
                    if (target != null) {
                        val tapped = SystemClock.elapsedRealtimeNanos()
                        target.click()
                        withContext(Dispatchers.Main) { AnswerJourneyObservation.trace?.mark(
                            AnswerJourneyPhase.SYSTEMUI_ACTION_TAPPED, atNanos = tapped) }
                        return@systemUi
                    }
            } else if (!scrolled) {
                device.findObject(By.res("com.android.systemui", "notification_stack_scroller"))?.scroll(Direction.DOWN, 0.6f)
                scrolled = true
            }
            retry.onceAfter(1500) { device.pressBack(); device.openNotification(); expanded = false; scrolled = false }
            delay(10)
        }
    }
    collapseNotificationTestShade()
}

/** Real Home navigation for scenarios which require another app in the foreground. */
internal suspend fun backgroundNotificationTestApp() = systemUi { device ->
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launcher = checkNotNull(home.resolveActivity(context.packageManager)?.packageName) { "No launcher package" }
    NotificationUiRetry(SystemClock::uptimeMillis).home(device::pressHome, { device.currentPackageName == launcher },
        { context.startActivity(home) })
}
