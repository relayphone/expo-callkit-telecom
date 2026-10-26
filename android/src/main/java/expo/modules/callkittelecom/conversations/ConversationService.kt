package expo.modules.callkittelecom.conversations

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Bundle
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
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
        fun incomingChannelId(context: Context): String {
            val metadata = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
            val ringtone = metadata?.getString("ExpoCallKitTelecomDefaultRingtone") ?: "default"
            return "conversations-incoming-v2-${ringtone.hashCode().toUInt().toString(16)}"
        }
        fun show(context: Context, id: String, state: Map<String, Any?>) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val metadata = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
            val ringtone = metadata?.getString("ExpoCallKitTelecomDefaultRingtone") ?: "default"
            val resource = context.resources.getIdentifier(ringtone.substringBeforeLast('.'), "raw", context.packageName)
            val sound = if (resource != 0) Uri.parse("android.resource://${context.packageName}/$resource") else Settings.System.DEFAULT_RINGTONE_URI
            val ringing = state["status"] == "ringing"
            val channelId = if (ringing) incomingChannelId(context) else "conversations-ongoing-v2"
            val channel = NotificationChannel(channelId, if (ringing) "Incoming calls" else "Ongoing calls",
                if (ringing) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW)
            channel.setSound(if (ringing) sound else null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            manager.createNotificationChannel(channel)
            fun action(name: String) = PendingIntent.getBroadcast(context, (id + name).hashCode(),
                Intent(context, ConversationReceiver::class.java).setAction(name).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val member = (state["members"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val callerUri = callerAddress(context, member)?.toString()
            val person = Person.Builder().setUri(callerUri).setName(member
                ?.get("displayName")?.toString() ?: "Caller").setImportant(true).build()
            val builder = NotificationCompat.Builder(context, channelId)
                .addPerson(person)
                .setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(PendingIntent.getActivity(context, id.hashCode(),
                    Intent(context, ConversationActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), state["status"] == "ringing")
            builder.setStyle(if (state["status"] == "ringing") NotificationCompat.CallStyle.forIncomingCall(person, action("end"), action("join"))
                else NotificationCompat.CallStyle.forOngoingCall(person, action("end")))
            val notificationId = notifications[id]?.first ?: nextId++
            notifications[id] = notificationId to builder.build()
            // Enqueue synchronously so a subsequent filtered-call cancellation records the
            // caller attempt even if the foreground service has not started yet.
            manager.notify(notificationId, notifications[id]!!.second)
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

/** A notification opens the app's own conversation UI; the library owns no call screen. */
class ConversationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
        finish()
    }
}
