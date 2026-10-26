package expo.modules.callkittelecom.conversations

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** CallStyle notification plus phoneCall foreground service for all supported Android versions. */
class ConversationService : Service() {
    companion object {
        private val notifications = linkedMapOf<String, Pair<Int, Notification>>()
        private var nextId = 7000
        fun show(context: Context, id: String, state: Map<String, Any?>) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("conversations", "Calls", NotificationManager.IMPORTANCE_HIGH))
            fun action(name: String) = PendingIntent.getBroadcast(context, (id + name).hashCode(),
                Intent(context, ConversationReceiver::class.java).setAction(name).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val person = Person.Builder().setName(((state["members"] as? List<*>)?.firstOrNull() as? Map<*, *>)
                ?.get("displayName")?.toString() ?: "Caller").setImportant(true).build()
            val builder = NotificationCompat.Builder(context, "conversations")
                .setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
            builder.setStyle(if (state["status"] == "ringing") NotificationCompat.CallStyle.forIncomingCall(person, action("end"), action("join"))
                else NotificationCompat.CallStyle.forOngoingCall(person, action("end")))
            val notificationId = notifications[id]?.first ?: nextId++
            notifications[id] = notificationId to builder.build()
            ContextCompat.startForegroundService(context, Intent(context, ConversationService::class.java))
        }
        fun remove(context: Context, id: String) {
            notifications.remove(id)?.let { context.getSystemService(NotificationManager::class.java).cancel(it.first) }
            context.startService(Intent(context, ConversationService::class.java))
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = notifications.values.firstOrNull()
        if (first == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, first.first, first.second, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        notifications.values.drop(1).forEach { getSystemService(NotificationManager::class.java).notify(it.first, it.second) }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

class ConversationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        val engine = ConversationEngine.get(context)
        engine.jobs.launch {
            try { if (intent.action == "join") engine.join(id) else engine.end(id) }
            catch (_: Exception) { /* The native engine owns failure and terminal state. */ }
            finally { pending.finish() }
        }
    }
}
