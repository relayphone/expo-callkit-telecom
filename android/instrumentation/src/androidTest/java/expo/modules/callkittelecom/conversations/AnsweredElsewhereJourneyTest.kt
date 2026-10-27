package expo.modules.callkittelecom.conversations

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real API35 Core call and visible SystemUI CallStyle card; no cloud-FCM claim. */
@RunWith(AndroidJUnit4::class)
class AnsweredElsewhereJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun now() = SystemClock.elapsedRealtimeNanos()
    @Volatile private var lastUi: Map<String, Any?> = emptyMap()
    private var expansionAttempts = 0
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private data class VisibleRing(val packageName: String, val windowId: Int,
        val caller: Boolean, val answer: Boolean, val decline: Boolean) {
        val present get() = caller && answer && decline
        fun json() = mapOf("packageName" to packageName, "windowId" to windowId, "callerVisible" to caller,
            "answerVisible" to answer, "declineVisible" to decline)
    }

    private fun displayState(): Map<String, Any?> = mapOf(
        "interactive" to context.getSystemService(PowerManager::class.java).isInteractive,
        "keyguardLocked" to context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)

    /** Emulator boot/build may outlast its screen timeout. Establish UI conditions before ringing. */
    private suspend fun prepareDisplay() = withContext(Dispatchers.IO) {
        withTimeout(5000) {
            shell("timeout -k 1 1 input keyevent KEYCODE_WAKEUP")
            shell("timeout -k 1 1 wm dismiss-keyguard")
            while (true) {
                val state = displayState()
                lastUi = state + ("expansionAttempts" to expansionAttempts)
                if (state["interactive"] == true && state["keyguardLocked"] == false) break
                delay(25)
            }
        }
    }

    /** A missing/different active window is inconclusive, never evidence of dismissal. */
    private fun visibleRing(label: String): VisibleRing? {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        lastUi = displayState() + mapOf("rootAvailable" to (root != null),
            "rootPackage" to root?.packageName?.toString(), "windowId" to root?.windowId,
            "expansionAttempts" to expansionAttempts)
        if (root == null || root.packageName?.toString() != "com.android.systemui" || root.windowId < 0) return null
        fun visible(value: String) = root.findAccessibilityNodeInfosByText(value).any { node ->
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            node.isVisibleToUser && !bounds.isEmpty &&
                (node.text?.toString()?.contains(value) == true || node.contentDescription?.toString()?.contains(value) == true)
        }
        val observed = VisibleRing(root.packageName.toString(), root.windowId, visible(label), visible("Answer"), visible("Decline"))
        lastUi = lastUi + observed.json()
        if (observed.caller && !observed.present) {
            // Expand the notification's public accessibility affordance if the shade collapsed it.
            val expand = root.findAccessibilityNodeInfosByViewId("android:id/expand_button")
                .firstOrNull { it.isVisibleToUser && it.isClickable && it.contentDescription?.toString() == "Expand" }
            if (expand?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) expansionAttempts++
        }
        lastUi = lastUi + ("expansionAttempts" to expansionAttempts)
        return observed
    }

    private suspend fun showRing(label: String): VisibleRing = withContext(Dispatchers.IO) {
        shell("cmd statusbar expand-notifications")
        withTimeout(5000) {
            var result: VisibleRing? = null
            while (result?.present != true) { result = visibleRing(label); if (result?.present != true) delay(25) }
            result!!
        }
    }

    private data class UiGone(val observedNanos: Long, val windowId: Int, val observations: Int)
    private suspend fun ringGone(label: String, expectedWindowId: Int): UiGone = withContext(Dispatchers.IO) {
        var absent = 0
        var observedWindow = -1
        while (absent < 2) {
            val observation = visibleRing(label)
            if (observation == null || observation.windowId != expectedWindowId) {
                absent = 0
                shell("cmd statusbar expand-notifications")
            } else {
                observedWindow = observation.windowId
                absent = if (!observation.caller) absent + 1 else 0
            }
            if (absent < 2) delay(20)
        }
        UiGone(now(), observedWindow, absent)
    }

    private fun notificationPresent(label: String): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { notification ->
            notification.notification.category == Notification.CATEGORY_CALL &&
                notification.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == label
        }

    private data class NativeEnd(val observedNanos: Long, val code: Int, val reason: String)
    private data class Completion(val terminal: Long, val event: Long, val empty: Long,
        val notification: Long, val ui: UiGone, val native: NativeEnd)
    private suspend fun platformGone(callId: String): NativeEnd = withContext(Dispatchers.IO) {
        while (true) {
            // Telecom's own historical event contains the cause installed on the actual call.
            val dump = shell("dumpsys -t 5 telecom timeline")
            val event = dump.lineSequence().firstOrNull { it.contains(",$callId,SET_DISCONNECTED,") }
            val current = dump.lineSequence().any { it.trimStart().startsWith("[Call id=$callId,") }
            if (!current && event != null) {
                val code = when {
                    event.contains("Code: (REMOTE)") -> android.telecom.DisconnectCause.REMOTE
                    event.contains("Code: (ANSWERED_ELSEWHERE)") -> android.telecom.DisconnectCause.ANSWERED_ELSEWHERE
                    else -> error("Unexpected platform disconnect code")
                }
                val reason = Regex("Reason: \\(([^)]*)\\)").find(event)?.groupValues?.get(1)
                    ?: error("Missing platform disconnect reason")
                return@withContext NativeEnd(now(), code, reason)
            }
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("No platform termination")
    }

    @Test fun hostAndPushSignalsDismissTheVisibleRing() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val oldPolicy = engine.policy
            val oldEmitter = engine.emit
            val oldUnhandled = engine.actions.acceptUnhandled
            val output = File(context.filesDir, "answered-elsewhere-results.json")
            output.delete()
            val samples = mutableListOf<Map<String, Any?>>()
            var owned: String? = null
            var currentId: String? = null
            var currentEvent: String? = null
            var currentServer: String? = null
            var terminal = CompletableDeferred<Long>()
            var correlated = CompletableDeferred<Long>()
            var empty = CompletableDeferred<Long>()
            var eventCount = 0
            var handlerCalls = 0
            var phase = "prepare"
            engine.policy = JSONObject(mapOf("type" to "handler", "prewarm" to false))
            engine.actions.resetBridge(); engine.actions.attach(true, 1, engine.actions.epoch)
            engine.actions.acceptUnhandled = false
            engine.emit = { name, body ->
                if (name == "handlerAction") handlerCalls++
                if (name == "conversationsChanged") {
                    val ended = (body["conversations"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull {
                        it["id"] == currentId && it["status"] == "ended" &&
                            (it["ended"] as? Map<*, *>)?.get("reason") == "answeredElsewhere"
                    }
                    if (ended != null) terminal.complete(now())
                    if (terminal.isCompleted && (body["conversations"] as List<*>).none { (it as Map<*, *>)["id"] == currentId })
                        empty.complete(now())
                }
                if (name == "conversationEvent" && body["type"] == "answeredElsewhere") {
                    val payload = body["payload"] as Map<*, *>
                    assertEquals(currentId, payload["conversationId"])
                    assertEquals(currentEvent, payload["eventId"])
                    assertEquals(currentServer, payload["serverCallId"])
                    assertTrue("Terminal SDK snapshot must precede the correlated event", terminal.isCompleted)
                    eventCount++; correlated.complete(now())
                }
            }
            val automation = instrumentation.uiAutomation
            val oldServiceFlags = automation.serviceInfo.flags
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            try {
                for (source in listOf("host", "push")) for (index in 1..3) {
                    expansionAttempts = 0
                    phase = "$source/$index display-ready"
                    prepareDisplay()
                    phase = "$source/$index incoming"
                    withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(25) }
                    val key = "elsewhere-$source-$index-${SystemClock.elapsedRealtime()}"
                    val payload = mapOf("eventId" to "incoming-$key", "serverCallId" to key,
                        "caller" to mapOf("handleType" to "generic", "value" to key, "displayName" to key), "hasVideo" to false)
                    val incoming = engine.incoming(payload)
                    assertEquals("reported", incoming["status"])
                    val id = incoming["id"] as String
                    owned = id; currentId = id; currentEvent = "ended-$key"; currentServer = key
                    terminal = CompletableDeferred(); correlated = CompletableDeferred(); empty = CompletableDeferred(); eventCount = 0
                    val before = TelecomEvidence.calls()
                    assertEquals(1, before.size); assertTrue(before.single().contains("state=RINGING"))
                    val platformId = Regex("\\[Call id=([^,\\]]+)").find(before.single())!!.groupValues[1]
                    assertTrue(notificationPresent(key))
                    phase = "$source/$index visible-ring"
                    val uiBefore = showRing(key)
                    val end = mapOf("eventId" to currentEvent, "serverCallId" to key, "reason" to "answeredElsewhere")
                    val signal = now()
                    phase = "$source/$index signal-to-dismissal"
                    if (source == "host") assertTrue(engine.receiveCallEnded(end))
                    else NativeCallPush(engine) {}.handle(mapOf("messageType" to "callEnded", "callEnded" to JSONObject(end).toString()), true)
                    val accepted = now()
                    val completion = withTimeout(1000) { coroutineScope {
                        val ui = async { ringGone(key, uiBefore.windowId) }
                        val native = async { platformGone(platformId) }
                        val notification = async(Dispatchers.IO) {
                            while (notificationPresent(key)) delay(10)
                            now()
                        }
                        Completion(terminal.await(), correlated.await(), empty.await(),
                            notification.await(), ui.await(), native.await())
                    } }
                    val native = completion.native
                    val terminalAt = completion.terminal; val eventAt = completion.event
                    val notificationAt = completion.notification; val uiAt = completion.ui.observedNanos
                    assertEquals(android.telecom.DisconnectCause.REMOTE, native.code)
                    assertEquals("answeredElsewhere", native.reason)
                    assertEquals(1, eventCount); assertEquals(0, handlerCalls)
                    assertFalse((engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id })
                    assertTrue(terminalAt >= signal && eventAt >= terminalAt && completion.empty >= eventAt)
                    for (at in listOf(terminalAt, eventAt, completion.empty, notificationAt, uiAt, native.observedNanos))
                        assertTrue("Signal-to-completion exceeded the 1000 ms engineering budget", at - signal in 0..1_000_000_000L)
                    assertFalse(engine.receiveCallEnded(end))
                    delay(25); assertEquals(1, eventCount)
                    samples += mapOf("source" to source, "sampleIndex" to index, "conversationId" to id,
                        "serverCallId" to key, "eventId" to currentEvent, "uiBefore" to uiBefore.json(),
                        "signalNanos" to signal, "acceptedNanos" to accepted, "terminalSnapshotNanos" to terminalAt,
                        "eventNanos" to eventAt, "emptyHookNanos" to completion.empty,
                        "notificationGoneNanos" to notificationAt, "uiGoneNanos" to uiAt,
                        "platformGoneNanos" to native.observedNanos, "nativeCode" to native.code, "nativeReason" to native.reason,
                        "nativeEvidence" to "telecom-historical-SET_DISCONNECTED", "correlatedEvents" to eventCount,
                        "endHandlerCalls" to handlerCalls, "uiAbsenceObservations" to completion.ui.observations,
                        "uiAbsentWindowId" to completion.ui.windowId)
                    owned = null
                }
                output.writeText(JSONObject(mapOf("passed" to true, "budgetMs" to 1000, "samples" to samples,
                    "pushEntry" to "NativeCallPush.handle (not cloud FCM)")).toString())
            } catch (error: Throwable) {
                output.writeText(JSONObject(mapOf("passed" to false, "samples" to samples,
                    "phase" to phase, "failure" to error.javaClass.simpleName,
                    "uiObservation" to lastUi)).toString())
                throw error
            } finally {
                owned?.let { id -> runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) } }
                engine.emit = oldEmitter; engine.policy = oldPolicy; engine.actions.acceptUnhandled = oldUnhandled
                engine.actions.resetBridge()
                withContext(Dispatchers.IO) { shell("cmd statusbar collapse") }
                automation.serviceInfo = automation.serviceInfo.apply { flags = oldServiceFlags }
            }
        }
    }
}
