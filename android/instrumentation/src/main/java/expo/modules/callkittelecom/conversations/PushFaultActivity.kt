package expo.modules.callkittelecom.conversations

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.google.firebase.messaging.RemoteMessage
import expo.modules.callkittelecom.instrumentation.BuildConfig
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Shell-only entry point in the standalone instrumentation host. No FCM transport is simulated. */
class PushFaultActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try { PushFaultRuntime.command(applicationContext, intent) }
        finally { finish() }
    }
}

internal object PushFaultRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: PushFaultController? = null
    private var log: PushFaultLog? = null
    suspend fun cancelPending() { controller?.cancelPending() }

    fun command(context: Context, intent: Intent) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk"))
        val journal = log ?: PushFaultLog(Process.myPid()) { contents ->
            val file = AtomicFile(File(context.filesDir, "push-fault-events.json"))
            val stream = file.startWrite()
            try { stream.write(contents.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        }.also { log = it; it.clear() }
        val deliveries = controller ?: PushFaultController(scope, SystemClock::elapsedRealtimeNanos,
            journal::record, { request -> deliver(context, request) }).also { controller = it }
        fun text(name: String) = requireNotNull(intent.getStringExtra(name)).also {
            require(it.isNotBlank() && it.length <= 512)
        }
        when (intent.getStringExtra("command")) {
            "cold-start" -> coldStart(context, PushFaultDelivery(text("deliveryId"), text("callId"), text("eventId")), journal)
            "deliver", "drop" -> deliveries.enqueue(PushFaultDelivery(text("deliveryId"), text("callId"),
                text("eventId"), intent.getLongExtra("delayMillis", 0), intent.getStringExtra("command") == "drop"))
            "rebind-empty-token" -> rebind(context, text("deliveryId"), journal)
            "reset" -> { deliveries.reset(); journal.clear() }
            else -> error("Unknown push fixture command")
        }
    }

    private fun deliver(context: Context, request: PushFaultDelivery): Boolean {
        val push = IncomingCallPush(request.callId, request.eventId,
            mapOf("handleType" to "generic", "value" to "fixture-caller", "displayName" to request.callId))
        return CallPushIntake.handle(context, RemoteMessage.Builder("fixture-sender")
            .addData("messageType", "incomingCall")
            .addData("incomingCall", JSONObject(push.payload()).toString()).build())
    }

    private fun coldStart(context: Context, request: PushFaultDelivery, journal: PushFaultLog) {
        val engine = ConversationEngine.get(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        fun state() = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
            .firstOrNull { it["id"] == request.callId }
        fun action(name: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, ConversationReceiver::class.java).setAction(name).setData(Uri.Builder()
                .scheme("voip-conversation").authority(context.packageName).appendPath(request.callId).appendPath(name).build()),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        scope.launch {
            val previousMedia = engine.nativeMedia
            val previousHandler = ConversationActions.handler
            val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                ConversationControls.systemOutcomes.first { it.conversationId == request.callId &&
                    it.action == SystemCommandAction.ANSWER && it.source == SystemCommandSource.NOTIFICATION }
            }
            var answer: PendingIntent? = null
            try {
                engine.nativeMedia = AndroidNativeMedia.NONE
                ConversationActions.configure { ConversationActionResult.Proceed() }
                CallPushIntake.configure()
                ColdPushJourney(SystemClock::elapsedRealtimeNanos, journal::record).run(request,
                    Process.getStartElapsedRealtime() * 1_000_000, { deliver(context, request) },
                    ringing = { withTimeout(5000) { while (state()?.get("status") != "ringing") delay(10) } },
                    presented = { withTimeout(5000) {
                        while (answer == null) {
                            val card = manager.activeNotifications.singleOrNull {
                                it.notification.category == Notification.CATEGORY_CALL &&
                                    it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
                                        ?.name?.toString() == request.callId
                            }?.notification
                            val actions = card?.actions.orEmpty()
                            val accept = actions.singleOrNull { it.actionIntent == action("join") }
                            val decline = actions.singleOrNull { it.actionIntent == action("decline") }
                            if (accept != null && decline != null) answer = accept.actionIntent
                            else delay(10)
                        }
                        check(state()?.get("status") == "ringing")
                    } },
                    answer = {
                        checkNotNull(answer).send()
                        withTimeout(5000) {
                            check(outcome.await().outcome is ConversationCommandOutcome.Performed)
                            check(state()?.get("status") == "connecting")
                            engine.report(request.callId, mapOf("type" to "connected"))
                            while (state()?.get("status") != "connected") delay(10)
                        }
                    },
                    cleanup = {
                        withTimeout(5000) {
                            if (state() != null) engine.report(request.callId, mapOf("type" to "ended", "reason" to "remoteEnded"))
                            while (state() != null || manager.activeNotifications.any {
                                it.notification.category == Notification.CATEGORY_CALL }) delay(10)
                        }
                    })
            } catch (_: Exception) { /* The bounded journal retains the failed stage. */ }
            finally { withContext(NonCancellable) {
                outcome.cancelAndJoin()
                engine.nativeMedia = previousMedia
                ConversationActions.configure(previousHandler)
            } }
        }
    }

    private fun rebind(context: Context, id: String, journal: PushFaultLog) {
        val engine = ConversationEngine.get(context)
        val owner = engine.bridgeOwner
        val sender = engine.emit
        @Suppress("UNCHECKED_CAST")
        val original = (engine.pushTokenSnapshot()["token"] as? Map<String, Any?>)?.get("value") as? String
        try {
            engine.bind(Any()) { _, _ -> }
            for (absent in listOf(true, false)) {
                if (absent) engine.updatePushToken(null) else CallPushIntake.newToken(context, "")
                val before = engine.pushTokenSnapshot()
                engine.bind(Any()) { _, _ -> }
                val after = engine.pushTokenSnapshot()
                val token = after["token"] as? Map<*, *>
                journal.record(PushFaultEvent.TokenRebind(id, absent, token != null, token?.get("value") == "",
                    before["revision"] as Long, after["revision"] as Long, before == after,
                    SystemClock.elapsedRealtimeNanos()))
            }
        } finally {
            engine.updatePushToken(original)
            engine.bind(owner, sender)
        }
    }
}
