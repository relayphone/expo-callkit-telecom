package expo.modules.callkittelecom.conversations

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
            journal::record, { request ->
                val push = IncomingCallPush(request.callId, request.eventId,
                    mapOf("handleType" to "generic", "value" to "fixture-caller", "displayName" to "Push fixture"))
                CallPushIntake.handle(context, RemoteMessage.Builder("fixture-sender")
                    .addData("messageType", "incomingCall")
                    .addData("incomingCall", JSONObject(push.payload()).toString()).build())
            }).also { controller = it }
        fun text(name: String) = requireNotNull(intent.getStringExtra(name)).also {
            require(it.isNotBlank() && it.length <= 512)
        }
        when (intent.getStringExtra("command")) {
            "deliver", "drop" -> deliveries.enqueue(PushFaultDelivery(text("deliveryId"), text("callId"),
                text("eventId"), intent.getLongExtra("delayMillis", 0), intent.getStringExtra("command") == "drop"))
            "rebind-empty-token" -> rebind(context, text("deliveryId"), journal)
            "reset" -> { deliveries.reset(); journal.clear() }
            else -> error("Unknown push fixture command")
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
