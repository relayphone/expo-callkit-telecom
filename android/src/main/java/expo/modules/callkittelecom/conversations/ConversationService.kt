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
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.launch

/** CallStyle notification plus phoneCall foreground service for all supported Android versions. */
class ConversationService : Service() {
    companion object {
        private const val FOREGROUND_ID = 6999
        private val roster = CallNotificationRoster()
        private val waitingIds = linkedMapOf<String, Int>()
        private var foreground: Notification? = null
        private var nextId = 7000
        fun incomingChannelId(context: Context): String {
            val metadata = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
            val ringtone = metadata?.getString("ExpoCallKitTelecomDefaultRingtone") ?: "default"
            return "conversations-incoming-v2-${ringtone.hashCode().toUInt().toString(16)}"
        }
        fun show(context: Context, id: String, state: Map<String, Any?>) {
            if (roster.put(id, state)) render(context)
        }
        internal fun refresh(context: Context, states: List<Map<String, Any?>>) {
            if (roster.refresh(states)) render(context)
        }
        fun remove(context: Context, id: String) {
            if (roster.remove(id)) render(context)
        }
        private fun render(context: Context) {
            val plan = roster.plan()
            val manager = context.getSystemService(NotificationManager::class.java)
            val waiting = plan.waiting.map { it["id"] as String }.toSet()
            for (id in waitingIds.keys.toList()) if (id !in waiting) manager.cancel(waitingIds.remove(id)!!)
            foreground = plan.primary?.let { notification(context, it, plan.heldSummary) }
            for (state in plan.waiting) {
                val notificationId = waitingIds.getOrPut(state["id"] as String) { nextId++ }
                manager.notify(notificationId, notification(context, state, null))
            }
            // Ringing can post synchronously before a filtered-call cancellation, preserving
            // repeat-caller history. Ongoing CallStyle posts only through startForeground.
            if (plan.primary?.get("status") == "ringing") manager.notify(FOREGROUND_ID, foreground!!)
            val intent = Intent(context, ConversationService::class.java)
            if (foreground == null) {
                manager.cancel(FOREGROUND_ID)
                context.startService(intent)
            } else ContextCompat.startForegroundService(context, intent)
        }
        private fun notification(context: Context, state: Map<String, Any?>, heldSummary: String?): Notification {
            val id = state["id"] as String
            val manager = context.getSystemService(NotificationManager::class.java)
            val metadata = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
            val ringtone = metadata?.getString("ExpoCallKitTelecomDefaultRingtone") ?: "default"
            val resource = context.resources.getIdentifier(ringtone.substringBeforeLast('.'), "raw", context.packageName)
            val sound = if (resource != 0) Uri.parse("android.resource://${context.packageName}/$resource") else Settings.System.DEFAULT_RINGTONE_URI
            val ringing = state["status"] == "ringing"
            val defaultChannelId = if (ringing) incomingChannelId(context) else "conversations-ongoing-v2"
            fun action(name: String) = PendingIntent.getBroadcast(context, 0,
                Intent(context, ConversationReceiver::class.java).setAction(name)
                    .setData(Uri.Builder().scheme("voip-conversation").authority(context.packageName).appendPath(id).appendPath(name).build()).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val member = (state["members"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val payload = state["payload"] as? Map<*, *>
            val caller = (payload?.get("caller") as? Map<*, *>)?.let(NativeCaller::parse)
            val callerUri = caller?.address()?.toString() ?: callerAddress(context, member)?.toString()
            val content = ConversationPresentation.resolve(state, if (ringing) CallPhase.RINGING else CallPhase.ONGOING,
                CallNotificationContent(
                    title = caller?.displayName ?: member?.get("displayName")?.toString() ?: "Caller",
                    channelId = defaultChannelId,
                    heldSummary = heldSummary,
                    fullScreenIntent = if (ringing) PendingIntent.getActivity(context, 0,
                        Intent(context, ConversationActivity::class.java).setData(Uri.Builder().scheme("voip-conversation").authority(context.packageName).appendPath(id).build()),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null,
                ))
            val channelId = checkNotNull(content.channelId)
            if (channelId == defaultChannelId) {
                val channel = NotificationChannel(channelId, if (ringing) "Incoming calls" else "Ongoing calls",
                    if (ringing) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW)
                channel.setSound(if (ringing) sound else null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                manager.createNotificationChannel(channel)
            }
            val person = Person.Builder().setUri(callerUri).setKey(caller?.hostContactID)
                .setName(content.title).setIcon(content.avatar?.let { IconCompat.createFromIcon(context, it) })
                .setImportant(true).build()
            val builder = NotificationCompat.Builder(context, channelId)
                .addPerson(person)
                .setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(content.fullScreenIntent, ringing)
                .setContentIntent(content.contentIntent)
            if (heldSummary != null) {
                builder.setContentText(content.heldSummary)
                // Compat CallStyle reads extras directly when rendering on API 26–30.
                builder.addExtras(Bundle().apply { putCharSequence(NotificationCompat.EXTRA_TEXT, content.heldSummary) })
            }
            content.extraActions.forEach { builder.addAction(it) }
            builder.setStyle(if (ringing) NotificationCompat.CallStyle.forIncomingCall(person, action("end"), action("join"))
                else NotificationCompat.CallStyle.forOngoingCall(person, action("end")))
            return builder.build().apply {
                if (ringing) flags = flags or Notification.FLAG_INSISTENT
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = foreground
        if (notification == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
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
            try { if (intent.action == "join") engine.join(id, NativeActionOrigin.SYSTEM) else engine.end(id, NativeActionOrigin.SYSTEM) }
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
