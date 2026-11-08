package expo.modules.callkittelecom.conversations

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.telecom.CallControlScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the installed example's engine without replacing its Expo emitter or handler. */
@RunWith(AndroidJUnit4::class)
class AndroidBridgeJourneyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun read(name: String): JSONObject? = runCatching {
        JSONObject(File(context.filesDir, name).readText())
    }.getOrNull()
    private fun write(name: String, value: JSONObject) {
        val output = File(context.filesDir, name)
        val temporary = File(context.filesDir, "$name.tmp")
        temporary.writeText(value.toString())
        check(temporary.renameTo(output)) { "Cannot commit test evidence" }
    }
    private suspend fun until(label: String, predicate: suspend () -> Boolean) {
        val completed = withTimeoutOrNull(30_000) { while (!predicate()) delay(50); true }
        check(completed == true) { "Timed out waiting for $label" }
    }
    private suspend fun platformCalls(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val lines = shell("dumpsys -t 5 telecom").lines()
        val start = lines.indexOfFirst { it.trim() == "mCalls:" }
        check(start >= 0) { "Missing API35 CallsManager section" }
        val indent = lines[start].takeWhile { it.isWhitespace() }.length
        lines.drop(start + 1).takeWhile { it.isBlank() || it.takeWhile { c -> c.isWhitespace() }.length > indent }
            .filter { it.trimStart().startsWith("[Call id=") }.map { line ->
                val id = Regex("\\[Call id=([^,\\]]+)").find(line)?.groupValues?.get(1)
                val state = Regex("(?:^|[, ])state=([A-Z_]+)").find(line)?.groupValues?.get(1)
                check(id != null && state != null) { "Unrecognized platform call evidence" }
                id to state
            }
    }
    /** Read-only test correlation. Core-Telecom's API35 UUID is also CallsManager's call ID. */
    private suspend fun platformID(engine: ConversationEngine, id: String): String {
        val callsField = ConversationEngine::class.java.getDeclaredField("calls").apply { isAccessible = true }
        val call = (callsField.get(engine) as Map<*, *>)[id] ?: error("Missing native identity")
        val ready = call.javaClass.getDeclaredField("ready").apply { isAccessible = true }.get(call) as Deferred<*>
        return (ready.await() as CallControlScope).getCallId().toString()
    }

    @Test fun productionPushAndJavaScriptJourneys() = runBlocking {
        check(Build.VERSION.SDK_INT == 35) { "Journey evidence requires API35" }
        check(android.app.Application.getProcessName() == context.packageName) { "Wrong target process" }
        val runID = InstrumentationRegistry.getArguments().getString("runID") ?: error("Missing runID")
        check(Regex("[a-z0-9-]{1,80}").matches(runID))
        val oldMode = manager.currentInterruptionFilter
        val modes = mapOf(1 to "off", 2 to "priority", 3 to "on", 4 to "alarms")
        check(modes.containsKey(oldMode)) { "Unknown initial DND mode" }
        val signals = CopyOnWriteArrayList<NativeMediaSignal>()
        val observer = NativeMediaObserver { signals += it }
        val checkpoints = mutableListOf<Map<String, Any?>>()
        val reports = mutableListOf<Map<String, Any?>>()
        val platformIDs = mutableMapOf<String, String>()
        val warmups = mutableMapOf<String, Int>()
        val result = linkedMapOf<String, Any?>("schemaVersion" to 1, "runID" to runID,
            "packageName" to context.packageName, "pid" to Process.myPid(), "api" to Build.VERSION.SDK_INT,
            "processName" to android.app.Application.getProcessName(),
            "stage" to "running", "reports" to reports, "checkpoints" to checkpoints)
        val engine = withContext(Dispatchers.Main) { ConversationEngine.get(context) }
        suspend fun dnd(mode: String, expected: Int) {
            withContext(Dispatchers.IO) { shell("cmd notification set_dnd $mode") }
            until("DND $mode") { manager.currentInterruptionFilter == expected }
        }
        suspend fun push(name: String) {
            val id = "$runID-$name"
            val payload = JSONObject(mapOf("eventId" to "$id-push", "serverCallId" to id,
                "caller" to mapOf("handleType" to "generic", "value" to id, "displayName" to id), "hasVideo" to false))
            val outcome = withContext(Dispatchers.Main) {
                NativeCallPush(engine) { warmups[id] = (warmups[id] ?: 0) + 1 }
                    .handle(mapOf("messageType" to "incomingCall", "incomingCall" to payload.toString()), true)
            } ?: error("Production intake returned no outcome")
            val status = if (name == "filtered") "filtered" else "reported"
            check(outcome["status"] == status) { "Unexpected native intake outcome" }
            if (status == "reported") check(outcome["id"] == id) { "Native incoming identity changed" }
            else check(outcome["reason"] == "doNotDisturb")
            reports += buildMap { put("id", id); put("status", status)
                if (status == "filtered") put("reason", outcome["reason"]) }
        }
        val stages = listOf(
            Triple("incoming:ringing", "ringing", "RINGING"),
            Triple("incoming:connected", "connected", "ACTIVE"),
            Triple("incoming:held", "held", "ON_HOLD"),
            Triple("incoming:removed", "absent", "ABSENT"),
            Triple("outgoing:connected", "connected", "ACTIVE"),
            Triple("outgoing:removed", "absent", "ABSENT"),
            Triple("elsewhere:ringing", "ringing", "RINGING"),
            Triple("elsewhere:removed", "absent", "ABSENT"),
            Triple("filtered:removed", "absent", "ABSENT"))
        NativeMediaSignals.observers += observer
        try {
            dnd("off", NotificationManager.INTERRUPTION_FILTER_ALL)
            check(platformCalls().isEmpty()) { "Unrelated platform call present" }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: error("Missing example activity")
            launch.action = Intent.ACTION_VIEW
            launch.data = Uri.parse("conversations-example://android-journey?runID=$runID")
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            for ((index, stage) in stages.withIndex()) {
                val (step, status, telecom) = stage
                val name = step.substringBefore(':')
                val id = "$runID-$name"
                until("JavaScript $step") {
                    val js = read("android-journey.json")
                    check(js?.optString("stage") != "failed") { "JavaScript journey failed" }
                    js?.optString("runID") == runID && js.optInt("sequence") == index + 1 && js.optString("stage") == step
                }
                when (step) {
                    "incoming:ringing", "elsewhere:ringing" -> push(name)
                    "elsewhere:removed" -> withContext(Dispatchers.Main) {
                        val ended = JSONObject(mapOf("eventId" to "$id-ended", "serverCallId" to id, "reason" to "answeredElsewhere"))
                        NativeCallPush(engine) { error("End signal warmed JavaScript") }
                            .handle(mapOf("messageType" to "callEnded", "callEnded" to ended.toString()), true)
                    }
                    "filtered:removed" -> { dnd("on", NotificationManager.INTERRUPTION_FILTER_NONE); push(name) }
                }
                var nativeStatus = "unknown"
                var notifications = -1
                var notificationID: String? = null
                until("native/platform $step") {
                    withContext(Dispatchers.Main) {
                        val calls = engine.snapshot["conversations"] as List<*>
                        val call = calls.map { it as Map<*, *> }.singleOrNull { it["id"] == id }
                        nativeStatus = call?.get("status") as? String ?: "absent"
                        check(calls.size == if (call == null) 0 else 1) { "Unrelated native call present" }
                        if (call != null) platformIDs[id] = platformID(engine, id)
                    }
                    val platform = platformCalls()
                    val active = manager.activeNotifications.filter { it.notification.category == Notification.CATEGORY_CALL }
                    notifications = active.size
                    val notification = active.singleOrNull()?.notification
                    val uri = notification?.extras?.getParcelable("android.callPerson", android.app.Person::class.java)?.uri
                    notificationID = when (uri) {
                        "sip:$id", "voip:${context.packageName}/$id" -> id
                        null -> null
                        else -> "unmatched"
                    }
                    val callStyle = notification?.extras?.getString("android.template") == "android.app.Notification\$CallStyle"
                    val expectedID = platformIDs[id]
                    val platformMatches = if (status == "absent") platform.isEmpty()
                        else platform == listOf(expectedID to telecom)
                    nativeStatus == status && platformMatches && notifications == (if (status == "absent") 0 else 1) &&
                        (status == "absent" || (notificationID == id && callStyle))
                }
                // Drain main-queued native observations before enforcing no media/warmup for filtered calls.
                withContext(Dispatchers.Main) { yield() }
                val mediaStarted = signals.count { it.serverCallId == id && it.stage in setOf(
                    NativeMediaStage.PREWARM_STARTED, NativeMediaStage.MEDIA_CONNECT_STARTED,
                    NativeMediaStage.CAPTURE_STARTED, NativeMediaStage.PLAYBACK_STARTED) }
                check(mediaStarted == 0) { "App-managed example unexpectedly started native media" }
                check((warmups[id] ?: 0) == if (name in listOf("incoming", "elsewhere")) 1 else 0) { "Unexpected JavaScript warmup" }
                checkpoints += mapOf("sequence" to index + 1, "step" to step, "id" to id, "status" to nativeStatus,
                    "telecom" to telecom, "platformID" to platformIDs[id], "notifications" to notifications,
                    "notificationID" to notificationID,
                    "warmups" to (warmups[id] ?: 0), "mediaStarted" to mediaStarted)
                write("android-native-journey.json", JSONObject(result))
                write("android-journey-ack.json", JSONObject(mapOf("runID" to runID, "sequence" to index + 1)))
            }
            until("JavaScript completion") {
                val js = read("android-journey.json")
                check(js?.optString("stage") != "failed") { "JavaScript journey failed" }
                js?.optString("runID") == runID && js.optString("stage") == "passed"
            }
            result["stage"] = "passed"
        } catch (error: Throwable) {
            result["stage"] = "failed"; result["failure"] = error.javaClass.simpleName
            throw error
        } finally {
            write("android-native-journey.json", JSONObject(result))
            NativeMediaSignals.observers -= observer
            withContext(NonCancellable) { dnd(modes.getValue(oldMode), oldMode) }
        }
    }
}
