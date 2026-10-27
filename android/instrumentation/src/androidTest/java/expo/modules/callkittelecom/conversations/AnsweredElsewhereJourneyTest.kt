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
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile private var lastPlatform: Map<String, Any?> = emptyMap()
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
    private data class NodeScan(val nodes: List<AccessibilityNodeInfo>, val complete: Boolean)
    private fun nodeTree(root: AccessibilityNodeInfo, stopAt: String? = null): NodeScan {
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var complete = true
        while (pending.isNotEmpty() && nodes.size < 512) {
            val node = pending.removeLast()
            nodes += node
            if (stopAt != null && node.viewIdResourceName == stopAt) return NodeScan(nodes, false)
            if (node.childCount > 32) complete = false
            for (index in minOf(node.childCount, 32) - 1 downTo 0) {
                if (pending.size >= 512) { complete = false; break }
                val child = runCatching { node.getChild(index) }.getOrNull()
                if (child == null) complete = false else pending.add(child)
            }
        }
        return NodeScan(nodes, complete && pending.isEmpty())
    }

    private fun notificationStack(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        nodeTree(root, "com.android.systemui:id/notification_stack_scroller").nodes
        .firstOrNull { it.viewIdResourceName == "com.android.systemui:id/notification_stack_scroller" }

    private fun visibleRing(label: String): VisibleRing? {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        lastUi = displayState() + mapOf("rootAvailable" to (root != null),
            "rootPackage" to root?.packageName?.toString(), "windowId" to root?.windowId,
            "expansionAttempts" to expansionAttempts)
        if (root == null || root.packageName?.toString() != "com.android.systemui" || root.windowId < 0 ||
            root.viewIdResourceName != "com.android.systemui:id/legacy_window_root") return null
        val stack = notificationStack(root) ?: return null
        val stackBounds = Rect(); stack.getBoundsInScreen(stackBounds)
        if (!stack.isVisibleToUser || stackBounds.isEmpty) return null
        val scan = nodeTree(stack)
        val nodes = scan.nodes
        lastUi = lastUi + mapOf("shadeVerified" to true, "notificationNodes" to nodes.size,
            "traversalComplete" to scan.complete)
        fun visible(value: String) = nodes.any { node ->
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            node.isVisibleToUser && !bounds.isEmpty &&
                (node.text?.toString()?.contains(value) == true || node.contentDescription?.toString()?.contains(value) == true)
        }
        val observed = VisibleRing(root.packageName.toString(), root.windowId, visible(label), visible("Answer"), visible("Decline"))
        lastUi = lastUi + observed.json()
        // Partial trees can prove visible presence, never absence of a still-visible card.
        if (!observed.caller && !scan.complete) return null
        if (observed.caller && !observed.present) {
            // Expand the notification's public accessibility affordance if the shade collapsed it.
            val expand = nodes.firstOrNull { it.viewIdResourceName == "android:id/expand_button" &&
                it.isVisibleToUser && it.isClickable && it.contentDescription?.toString() == "Expand" }
            if (expand?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) expansionAttempts++
        }
        lastUi = lastUi + ("expansionAttempts" to expansionAttempts)
        return observed
    }

    /** Failure-only, bounded structure: never retain node text, descriptions, or window titles. */
    private fun uiStructure(label: String): Map<String, Any?> {
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        val deadline = now() + 2_000_000_000L
        fun tree(root: AccessibilityNodeInfo?, limit: Int): Map<String, Any?> {
            if (root == null) return mapOf("rootAvailable" to false)
            val nodes = mutableListOf<Map<String, Any?>>()
            val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && nodes.size < limit && now() < deadline) {
                val (node, depth) = queue.removeFirst()
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                fun contains(value: String) = node.text?.contains(value) == true || node.contentDescription?.contains(value) == true
                nodes += mapOf("depth" to depth, "class" to node.className?.toString()?.take(160),
                    "resourceId" to node.viewIdResourceName?.take(160), "visible" to node.isVisibleToUser,
                    "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    "clickable" to node.isClickable, "children" to node.childCount,
                    "textLength" to (node.text?.length ?: 0), "descriptionLength" to (node.contentDescription?.length ?: 0),
                    "callerFull" to contains(label), "callerPrefix" to contains(label.substringBeforeLast('-')),
                    "answer" to contains("Answer"), "decline" to contains("Decline"),
                    "expand" to contains("Expand"), "collapse" to contains("Collapse"))
                if (depth < 16) for (index in 0 until minOf(node.childCount, 32)) {
                    if (queue.size >= 256 || now() >= deadline) break
                    node.getChild(index)?.let { queue.add(it to depth + 1) }
                }
            }
            return mapOf("rootAvailable" to true, "windowId" to root.windowId,
                "rootClass" to root.className?.toString()?.take(160), "rootResourceId" to root.viewIdResourceName?.take(160),
                "rootChildren" to root.childCount, "truncated" to queue.isNotEmpty(), "nodes" to nodes)
        }
        val activeRoot = automation.rootInActiveWindow
        val active = tree(activeRoot, 128)
        val callContent = activeRoot?.let(::notificationStack)?.let { tree(it, 128) }
        val searches = listOf("callerFull" to label, "callerPrefix" to label.substringBeforeLast('-'),
            "answer" to "Answer", "decline" to "Decline").associate { (key, query) ->
            val matches = if (now() < deadline) activeRoot?.findAccessibilityNodeInfosByText(query).orEmpty() else emptyList()
            key to mapOf("matches" to matches.size, "visibleMatches" to matches.count { node ->
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                node.isVisibleToUser && !bounds.isEmpty
            })
        }
        try {
            // Inventory other visible windows only after failure; the pass/fail oracle is unchanged.
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val windows = automation.windows.take(6).map { window ->
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                mapOf("id" to window.id, "type" to window.type, "layer" to window.layer,
                    "active" to window.isActive, "focused" to window.isFocused,
                    "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    "tree" to tree(window.root, 64))
            }
            return mapOf("serviceFlags" to originalFlags, "activeTree" to active, "notificationTree" to callContent,
                "searchCounts" to searches, "windows" to windows,
                "budgetMs" to 2000, "limitReached" to (now() >= deadline))
        } finally { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
    }

    private suspend fun showRing(label: String): VisibleRing = withContext(Dispatchers.IO) {
        shell("cmd statusbar expand-notifications")
        try { withTimeout(5000) {
            var result: VisibleRing? = null
            while (result?.present != true) { result = visibleRing(label); if (result?.present != true) delay(25) }
            result!!
        } } catch (error: TimeoutCancellationException) {
            lastUi = lastUi + ("structure" to runCatching { uiStructure(label) }
                .getOrElse { mapOf("diagnosticError" to it.javaClass.simpleName) })
            throw error
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
            val requested = now()
            val dump = shell("dumpsys -t 5 telecom timeline")
            val event = dump.lineSequence().firstOrNull { it.contains(",$callId,SET_DISCONNECTED,") }
            val current = dump.lineSequence().any { it.trimStart().startsWith("[Call id=$callId,") }
            lastPlatform = mapOf("requestedNanos" to requested, "returnedNanos" to now(),
                "trackedCall" to current, "disconnectEventFound" to (event != null),
                "remoteCodeFound" to (event?.contains("Code: (REMOTE)") == true),
                "answeredReasonFound" to (event?.contains("Reason: (answeredElsewhere)") == true))
            if (!current && event != null) {
                val code = when {
                    event.contains("Code: (REMOTE)") -> android.telecom.DisconnectCause.REMOTE
                    event.contains("Code: (ANSWERED_ELSEWHERE)") -> android.telecom.DisconnectCause.ANSWERED_ELSEWHERE
                    else -> error("Unexpected platform disconnect code")
                }
                val reason = Regex("Reason: \\(([^)]*)\\)").find(event)?.groupValues?.get(1)
                    ?: error("Missing platform disconnect reason")
                lastPlatform = lastPlatform + mapOf("nativeCode" to code,
                    "nativeReasonMatchesExpected" to (reason == "answeredElsewhere"))
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
            var timings = ConcurrentHashMap<String, Long>()
            var observerErrors = ConcurrentHashMap<String, String>()
            var postFailure: Map<String, Any?> = emptyMap()
            fun mark(key: String): Long {
                timings.putIfAbsent(key, now())
                return timings[key]!!
            }
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
                    if (ended != null) terminal.complete(mark("terminalSnapshotNanos"))
                    if (terminal.isCompleted && (body["conversations"] as List<*>).none { (it as Map<*, *>)["id"] == currentId })
                        empty.complete(mark("emptyHookNanos"))
                }
                if (name == "conversationEvent" && body["type"] == "answeredElsewhere") {
                    val payload = body["payload"] as Map<*, *>
                    assertEquals(currentId, payload["conversationId"])
                    assertEquals(currentEvent, payload["eventId"])
                    assertEquals(currentServer, payload["serverCallId"])
                    assertTrue("Terminal SDK snapshot must precede the correlated event", terminal.isCompleted)
                    eventCount++; correlated.complete(mark("eventNanos"))
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
                    timings = ConcurrentHashMap(); observerErrors = ConcurrentHashMap(); postFailure = emptyMap()
                    lastPlatform = emptyMap()
                    val before = TelecomEvidence.calls()
                    assertEquals(1, before.size); assertTrue(before.single().contains("state=RINGING"))
                    val platformId = Regex("\\[Call id=([^,\\]]+)").find(before.single())!!.groupValues[1]
                    assertTrue(notificationPresent(key))
                    phase = "$source/$index visible-ring"
                    val uiBefore = showRing(key)
                    val end = mapOf("eventId" to currentEvent, "serverCallId" to key, "reason" to "answeredElsewhere")
                    val signal = now()
                    timings["signalNanos"] = signal
                    phase = "$source/$index signal-to-dismissal"
                    if (source == "host") assertTrue(engine.receiveCallEnded(end))
                    else NativeCallPush(engine) {}.handle(mapOf("messageType" to "callEnded", "callEnded" to JSONObject(end).toString()), true)
                    val accepted = now()
                    timings["acceptedNanos"] = accepted
                    val completion = supervisorScope {
                        val ui = async {
                            try { ringGone(key, uiBefore.windowId).also { timings["uiGoneNanos"] = it.observedNanos } }
                            catch (error: Exception) {
                                if (error !is CancellationException) observerErrors["ui"] = error.javaClass.simpleName
                                throw error
                            }
                        }
                        val native = async {
                            try { platformGone(platformId).also { timings["platformGoneNanos"] = it.observedNanos } }
                            catch (error: Exception) {
                                if (error !is CancellationException) observerErrors["platform"] = error.javaClass.simpleName
                                throw error
                            }
                        }
                        val notification = async(Dispatchers.IO) {
                            while (notificationPresent(key)) delay(10)
                            now().also { timings["notificationGoneNanos"] = it }
                        }
                        try {
                            withTimeout(1000) { Completion(terminal.await(), correlated.await(), empty.await(),
                                notification.await(), ui.await(), native.await()) }
                        } catch (error: Throwable) {
                            // The strict verdict has already failed. Keep these same observers
                            // alive briefly to measure completion rather than hiding the slow stage.
                            timings["strictFailureNanos"] = now()
                            val settled = withTimeoutOrNull(5000) { joinAll(ui, native, notification); true } == true
                            val core = runCatching { withTimeout(2000) { TelecomEvidence.calls() } }
                            postFailure = mapOf("observerBudgetMs" to 5000, "allObserversFinished" to settled,
                                "observedNanos" to now(), "coreCallsRemaining" to core.getOrNull()?.size,
                                "ownedCoreCallRemaining" to core.getOrNull()?.any { it.contains("[Call id=$platformId,") },
                                "coreProbeError" to core.exceptionOrNull()?.javaClass?.simpleName,
                                "notificationPresent" to notificationPresent(key))
                            throw error
                        } finally { ui.cancel(); native.cancel(); notification.cancel() }
                    }
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
                val current = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
                    .firstOrNull { it["id"] == currentId }
                output.writeText(JSONObject(mapOf("passed" to false, "samples" to samples,
                    "phase" to phase, "failure" to error.javaClass.simpleName,
                    "partialTimings" to timings.toMap(), "observerErrors" to observerErrors.toMap(),
                    "postFailure" to postFailure, "platformObservation" to lastPlatform,
                    "sdkObservation" to mapOf("present" to (current != null), "status" to current?.get("status"),
                        "endedReason" to (current?.get("ended") as? Map<*, *>)?.get("reason"),
                        "terminalObserved" to terminal.isCompleted, "eventObserved" to correlated.isCompleted,
                        "emptyObserved" to empty.isCompleted), "uiObservation" to lastUi)).toString())
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
