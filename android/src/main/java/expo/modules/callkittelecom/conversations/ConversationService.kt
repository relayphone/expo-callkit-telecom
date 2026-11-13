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
    private data class Card(val id: String, val phase: CallPhase, val content: CallNotificationContent,
                            val callerUri: String?, val callerKey: String?, val ending: Boolean = false) {
        // Android icons/actions may change in place, so they must be rebuilt on each snapshot.
        val hasMutableContent: Boolean get() = content.avatar != null || content.extraActions.isNotEmpty()
    }
    private data class WaitingCard(val notificationId: Int, val card: Card)
    companion object {
        private const val FOREGROUND_ID = 6999
        private val roster = CallNotificationRoster()
        private val endingIds = mutableMapOf<String, Int>()
        private val waitingCards = linkedMapOf<String, WaitingCard>()
        private var foregroundCard: Card? = null
        private var foreground: Notification? = null
        private var foregroundId: Int? = null
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
        internal fun refreshPresentation(context: Context) {
            val states = roster.liveStates()
            if (states.isEmpty()) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val posted = manager.activeNotifications.filter { it.tag == null }.associateBy { it.id }
            val heldSummary = roster.plan(foregroundCard?.id).heldSummary
            for (state in states) {
                val id = state["id"] as String
                var refreshed = card(context, state, if (foregroundCard?.id == id) heldSummary else null)
                val primary = foregroundCard?.let { it.id == id && !it.ending } == true
                val waiting = waitingCards[id]
                val notificationId = if (primary) foregroundId else waiting?.takeUnless { it.card.ending }?.notificationId
                val previous = posted[notificationId] ?: continue
                // Keep the playing ring's channel, including its sound and vibration.
                if (refreshed.phase == CallPhase.RINGING) refreshed = refreshed.copy(
                    content = refreshed.content.copy(channelId = previous.notification.channelId,
                        fullScreenIntent = previous.notification.fullScreenIntent))
                // Preserve CallStyle eligibility without changing foreground ownership.
                val update = notification(context, refreshed).apply {
                    flags = flags or (previous.notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
                }
                if (primary) {
                    foregroundCard = refreshed
                    foreground = update
                } else waitingCards[id] = checkNotNull(waiting).copy(card = refreshed)
                manager.notify(checkNotNull(notificationId), update)
            }
        }
        fun remove(context: Context, id: String) {
            endingIds.remove(id)
            if (roster.remove(id)) render(context)
        }
        internal fun ending(context: Context, id: String) {
            endingIds.getOrPut(id) { if (foregroundCard?.id == id) checkNotNull(foregroundId)
                else waitingCards[id]?.notificationId ?: nextId++ }
            if (roster.ending(id)) render(context)
        }
        private fun render(context: Context) {
            val plan = roster.plan(foregroundCard?.id)
            val manager = context.getSystemService(NotificationManager::class.java)
            val waiting = plan.waiting.map { it["id"] as String }.toSet()
            val primaryId = plan.primary?.get("id") as? String
            val nextForegroundId = primaryId?.let { endingIds[it] ?: FOREGROUND_ID }
            for (id in waitingCards.keys.toList()) if (id !in waiting) {
                val removed = waitingCards.remove(id)!!
                if (removed.notificationId != nextForegroundId) manager.cancel(removed.notificationId)
            }
            foregroundId?.takeIf { it != nextForegroundId && it !in endingIds.values }?.let {
                manager.cancel(it)
            }
            val primary = plan.primary?.let { card(context, it, plan.heldSummary) }
            val primaryChanged = foregroundCard != primary || primary?.hasMutableContent == true
            if (primaryChanged) {
                foreground = primary?.let { notification(context, it) }
                foregroundCard = primary
                foregroundId = nextForegroundId
            }
            for (state in plan.waiting) {
                val id = state["id"] as String
                val content = card(context, state, null)
                val previous = waitingCards[id]
                if (previous?.card != content || content.hasMutableContent) {
                    val notificationId = endingIds[id] ?: previous?.notificationId ?: nextId++
                    manager.notify(notificationId, notification(context, content))
                    waitingCards[id] = WaitingCard(notificationId, content)
                }
            }
            // Snapshot-only changes still reach the customizer, but must not exhaust
            // Android's update quota and cause a later primary switch to be dropped.
            if (!primaryChanged) return
            // Ringing can post synchronously before a filtered-call cancellation, preserving
            // repeat-caller history. Ongoing CallStyle posts only through startForeground.
            if (plan.primary?.get("status") == "ringing") manager.notify(foregroundId!!, foreground!!)
            val intent = Intent(context, ConversationService::class.java)
            if (foreground == null) {
                context.startService(intent)
            } else ContextCompat.startForegroundService(context, intent)
        }
        private fun card(context: Context, state: Map<String, Any?>, heldSummary: String?): Card {
            val id = state["id"] as String
            if (state["status"] == "ending") return Card(id, CallPhase.ONGOING,
                CallNotificationContent(title = "Ending call…", channelId = "conversations-ongoing-v2"), null, null, ending = true)
            val ringing = state["status"] == "ringing"
            val phase = if (ringing) CallPhase.RINGING else CallPhase.ONGOING
            val defaultChannelId = if (ringing) incomingChannelId(context) else "conversations-ongoing-v2"
            val member = (state["members"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val payload = state["payload"] as? Map<*, *>
            val caller = (payload?.get("caller") as? Map<*, *>)?.let(NativeCaller::parse)
            val callerUri = caller?.address()?.toString() ?: callerAddress(context, member)?.toString()
            val content = ConversationPresentation.resolve(state, phase,
                CallNotificationContent(
                    title = caller?.displayName ?: member?.get("displayName")?.toString() ?: "Caller",
                    channelId = defaultChannelId,
                    heldSummary = heldSummary,
                    fullScreenIntent = if (ringing) PendingIntent.getActivity(context, 0,
                        Intent(context, ConversationActivity::class.java).setData(Uri.Builder().scheme("voip-conversation").authority(context.packageName).appendPath(id).build()),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null,
                ))
            return Card(id, phase, content.copy(extraActions = content.extraActions.toList(),
                heldSummary = if (heldSummary == null) null else content.heldSummary),
                callerUri, caller?.hostContactID)
        }
        private fun notification(context: Context, card: Card): Notification {
            val id = card.id
            val content = card.content
            val ringing = card.phase == CallPhase.RINGING
            val manager = context.getSystemService(NotificationManager::class.java)
            val defaultChannelId = if (ringing) incomingChannelId(context) else "conversations-ongoing-v2"
            val channelId = checkNotNull(content.channelId)
            if (channelId == defaultChannelId) {
                val metadata = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
                val ringtone = metadata?.getString("ExpoCallKitTelecomDefaultRingtone") ?: "default"
                val resource = context.resources.getIdentifier(ringtone.substringBeforeLast('.'), "raw", context.packageName)
                val sound = if (resource != 0) Uri.parse("android.resource://${context.packageName}/$resource") else Settings.System.DEFAULT_RINGTONE_URI
                val channel = NotificationChannel(channelId, if (ringing) "Incoming calls" else "Ongoing calls",
                    if (ringing) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW)
                channel.setSound(if (ringing) sound else null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                manager.createNotificationChannel(channel)
            }
            fun action(name: String) = PendingIntent.getBroadcast(context, 0,
                Intent(context, ConversationReceiver::class.java).setAction(name)
                    .setData(Uri.Builder().scheme("voip-conversation").authority(context.packageName).appendPath(id).appendPath(name).build()).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (card.ending) return NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_CALL).setContentTitle(content.title)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", action("retryEnd")).build()
            val person = Person.Builder().setUri(card.callerUri).setKey(card.callerKey)
                .setName(content.title).setIcon(content.avatar?.let { IconCompat.createFromIcon(context, it) })
                .setImportant(true).build()
            val builder = NotificationCompat.Builder(context, channelId)
                .addPerson(person)
                .setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(content.fullScreenIntent, ringing)
                .setContentIntent(content.contentIntent)
            if (content.heldSummary != null) {
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
    private var postedForeground: Notification? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = foreground
        if (notification == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (postedForeground !== notification) {
            ServiceCompat.startForeground(this, checkNotNull(foregroundId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            postedForeground = notification
        }
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
            try { when (intent.action) {
                "join" -> engine.join(id, NativeActionOrigin.SYSTEM)
                "retryEnd" -> engine.retryPlatformRemoval(id)
                else -> engine.end(id, NativeActionOrigin.SYSTEM)
            } }
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
