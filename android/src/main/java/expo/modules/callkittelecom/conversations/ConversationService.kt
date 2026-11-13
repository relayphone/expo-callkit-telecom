package expo.modules.callkittelecom.conversations

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Bundle
import android.os.SystemClock
import android.media.AudioAttributes
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.*

/** CallStyle notification plus phoneCall foreground service for all supported Android versions. */
class ConversationService : Service() {
    private sealed interface AvatarKey {
        data class Resource(val packageName: String?, val id: Int) : AvatarKey
        data class ContentUri(val uri: Uri?) : AvatarKey
        data class Instance(val icon: Icon) : AvatarKey
    }
    private data class Card(val id: String, val phase: CallPhase, val content: CallNotificationContent,
                            val callerUri: String?, val callerKey: String?, val ending: Boolean = false,
                            val avatarKey: AvatarKey? = null) {
        // Snapshot text now, since an app can reuse actions containing mutable CharSequences.
        private val actions = content.extraActions.map { it.title?.toString() to it.actionIntent }
        fun sameDisplay(other: Card): Boolean =
            id == other.id && phase == other.phase && callerUri == other.callerUri && callerKey == other.callerKey &&
                content.title == other.content.title && content.heldSummary == other.content.heldSummary &&
                actions == other.actions && content.channelId == other.content.channelId &&
                content.fullScreenIntent == other.content.fullScreenIntent && content.contentIntent == other.content.contentIntent &&
                avatarKey == other.avatarKey
    }
    private data class NotificationCard(val notificationId: Int, val card: Card)
    companion object {
        private const val FOREGROUND_ID = 6999
        private const val RINGING_POST_SEQUENCE = "expo.modules.callkittelecom.ringingPostSequence"
        private var nextRingingPost = 0L
        private val roster = CallNotificationRoster()
        private val endingIds = mutableMapOf<String, Int>()
        private val endingCards = mutableMapOf<String, Card>()
        private val waitingCards = linkedMapOf<String, NotificationCard>()
        private var foregroundCard: Card? = null
        private var foreground: Notification? = null
        private var foregroundId: Int? = null
        private var nextId = 7000
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val ringingSchedule = RingingAlertSchedule()
        private val pendingRinging = mutableMapOf<String, Job>()
        private val lastAlertedCards = mutableMapOf<String, NotificationCard>()
        private var instance: ConversationService? = null
        private var foregroundStartPending = false
        fun incomingChannelId(context: Context): String {
            val metadata = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
            val ringtone = metadata?.getString("ExpoCallKitTelecomDefaultRingtone") ?: "default"
            return "conversations-incoming-v2-${ringtone.hashCode().toUInt().toString(16)}"
        }
        fun show(context: Context, id: String, state: Map<String, Any?>) {
            if (roster.put(id, state)) render(context)
        }
        /** DND-suppressed calls must still enter Android's repeat-caller history. */
        internal fun recordFiltered(context: Context, state: Map<String, Any?>) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val id = nextId++
            manager.notify(id, notification(context, card(context, state, null)))
            manager.cancel(id)
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
                val refreshed = card(context, state, if (foregroundCard?.id == id) heldSummary else null, refreshAvatar = true)
                val primary = foregroundCard?.let { it.id == id && !it.ending } == true
                val waiting = waitingCards[id]
                val notificationId = if (primary) foregroundId else waiting?.takeUnless { it.card.ending }?.notificationId
                val previous = posted[notificationId] ?: continue
                val previousCard = if (primary) foregroundCard else waiting?.card
                if (previousCard?.sameDisplay(refreshed) == true) continue
                // Preserve CallStyle eligibility without changing foreground ownership.
                if (primary) {
                    foregroundCard = refreshed
                    foreground = notification(context, refreshed)
                } else waitingCards[id] = checkNotNull(waiting).copy(card = refreshed)
                if (refreshed.phase == CallPhase.RINGING) requestRingingPost(context, id, allowCreate = false)
                else manager.notify(checkNotNull(notificationId), notification(context, refreshed).apply {
                    flags = flags or (previous.notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
                })
            }
        }
        private fun requestRingingPost(context: Context, id: String, allowCreate: Boolean) {
            if (pendingRinging[id]?.isActive == true) return
            val application = context.applicationContext
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (true) {
                        val wait = ringingSchedule.delayMillis(SystemClock.elapsedRealtimeNanos())
                        if (wait > 0) { delay(wait); continue }
                        if (roster.liveStates().none { it["id"] == id && it["status"] == "ringing" }) return@launch
                        val primary = foregroundCard?.takeIf { it.id == id && it.phase == CallPhase.RINGING }
                        val waiting = waitingCards[id]?.takeIf { it.card.phase == CallPhase.RINGING }
                        val card = primary ?: waiting?.card ?: return@launch
                        val notificationId = if (primary != null) foregroundId else waiting?.notificationId
                        if (notificationId == null) return@launch
                        val previous = application.getSystemService(NotificationManager::class.java).activeNotifications
                            .firstOrNull { it.tag == null && it.id == notificationId }
                        if (previous == null && !allowCreate) return@launch
                        val last = lastAlertedCards[id]
                        if (previous != null && last?.notificationId == notificationId && last.card.sameDisplay(card) &&
                            (primary == null || previous.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) return@launch
                        val sequence = ++nextRingingPost
                        val update = notification(application, card).apply {
                            flags = flags or ((previous?.notification?.flags ?: 0) and Notification.FLAG_FOREGROUND_SERVICE)
                            extras.putLong(RINGING_POST_SEQUENCE, sequence)
                        }
                        application.getSystemService(NotificationManager::class.java).notify(notificationId, update)
                        observeRingingPost(application.getSystemService(NotificationManager::class.java), notificationId, sequence)
                        lastAlertedCards[id] = NotificationCard(notificationId, card)
                        if (primary != null) foreground = update
                        return@launch
                    }
                } finally {
                    if (pendingRinging[id] === currentCoroutineContext()[Job]) pendingRinging.remove(id)
                }
            }
            pendingRinging[id] = job
            job.start()
        }
        private fun observeRingingPost(manager: NotificationManager, notificationId: Int, sequence: Long) {
            // startForeground returns before Android publishes its notification. Keep the package
            // gate closed until this exact post is visible, then measure the interval in elapsed time.
            ringingSchedule.requested(sequence)
            scope.launch {
                try {
                    withTimeoutOrNull(RINGING_ALERT_INTERVAL_MILLIS) {
                        while (manager.activeNotifications.none { it.tag == null && it.id == notificationId &&
                            it.notification.extras.getLong(RINGING_POST_SEQUENCE) == sequence }) delay(10)
                    }
                } finally {
                    // Dropped or immediately removed posts also get a conservative elapsed anchor.
                    ringingSchedule.posted(sequence, SystemClock.elapsedRealtimeNanos())
                }
            }
        }
        fun remove(context: Context, id: String) {
            endingIds.remove(id)
            endingCards.remove(id)
            if (roster.remove(id)) render(context)
        }
        internal fun ending(context: Context, id: String) {
            endingIds.getOrPut(id) { if (foregroundCard?.id == id) checkNotNull(foregroundId)
                else waitingCards[id]?.notificationId ?: nextId++ }
            if (roster.ending(id)) render(context)
        }
        private fun render(context: Context) {
            val ringing = roster.liveStates().filter { it["status"] == "ringing" }.map { it["id"] }.toSet()
            for (id in pendingRinging.keys.toList()) if (id !in ringing) pendingRinging.remove(id)?.cancel()
            lastAlertedCards.keys.retainAll(ringing)
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
            val previousPrimary = foregroundCard
            val previousForegroundId = foregroundId
            val primary = plan.primary?.let { card(context, it, plan.heldSummary) }
            val primaryChanged = if (primary == null) foregroundCard != null else foregroundCard?.sameDisplay(primary) != true
            if (primaryChanged) {
                foreground = primary?.let { notification(context, it) }
                foregroundCard = primary
                foregroundId = nextForegroundId
            }
            for (state in plan.waiting) {
                val id = state["id"] as String
                val content = card(context, state, null)
                val previous = waitingCards[id]
                if (previous?.card?.sameDisplay(content) != true) {
                    val notificationId = endingIds[id] ?: previous?.notificationId ?: nextId++
                    waitingCards[id] = NotificationCard(notificationId, content)
                    if (content.phase == CallPhase.RINGING) requestRingingPost(context, id, allowCreate = true)
                    else manager.notify(notificationId, notification(context, content))
                }
            }
            // Snapshot-only changes still reach the customizer, but must not exhaust
            // Android's update quota and cause a later primary switch to be dropped.
            val intent = Intent(context, ConversationService::class.java)
            if (primary == null) {
                // A pending foreground start must reach onStartCommand and fulfill its deadline.
                if (foregroundStartPending) return
                val running = instance
                if (running != null) running.stopPresentation() else context.stopService(intent)
                return
            }
            if (!primaryChanged) return
            if (primary.phase == CallPhase.RINGING && previousPrimary?.id == primary.id &&
                previousForegroundId == foregroundId && instance?.postedForeground != null) {
                requestRingingPost(context, primary.id, allowCreate = false)
            } else if (!foregroundStartPending) {
                foregroundStartPending = true
                try { ContextCompat.startForegroundService(context, intent) }
                catch (error: Exception) { foregroundStartPending = false; throw error }
            }
        }
        private fun placeholder(context: Context): Notification {
            val channelId = "conversations-ongoing-v2"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            return NotificationCompat.Builder(context, channelId).setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Call").setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true).setOnlyAlertOnce(true).build()
        }
        private fun card(context: Context, state: Map<String, Any?>, heldSummary: String?, refreshAvatar: Boolean = false): Card {
            val id = state["id"] as String
            if (state["status"] == "ending") return endingCards.getOrPut(id) {
                val content = ConversationPresentation.resolve(state, CallPhase.ENDING,
                    CallNotificationContent(endingTitle = "Ending call…"))
                Card(id, CallPhase.ENDING, CallNotificationContent(title = content.endingTitle,
                    channelId = "conversations-ongoing-v2"), null, null, ending = true)
            }
            val ringing = state["status"] == "ringing"
            val phase = if (ringing) CallPhase.RINGING else CallPhase.ONGOING
            val defaultChannelId = if (ringing) incomingChannelId(context) else "conversations-ongoing-v2"
            val member = (state["members"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val payload = state["payload"] as? Map<*, *>
            val caller = (payload?.get("caller") as? Map<*, *>)?.let(NativeCaller::parse)
            val callerUri = caller?.address()?.toString() ?: callerAddress(context, member)?.toString()
            var content = ConversationPresentation.resolve(state, phase,
                CallNotificationContent(
                    title = caller?.displayName ?: member?.get("displayName")?.toString() ?: "Caller",
                    channelId = defaultChannelId,
                    heldSummary = heldSummary,
                    fullScreenIntent = if (ringing) PendingIntent.getActivity(context, 0,
                        Intent(context, ConversationActivity::class.java).setData(Uri.Builder().scheme("voip-conversation").authority(context.packageName).appendPath(id).build()),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null,
                ))
            if (ringing && !refreshAvatar) {
                val previous = foregroundCard?.takeIf { it.id == id } ?: waitingCards[id]?.card
                if (previous?.phase == CallPhase.RINGING) content = content.copy(avatar = previous.content.avatar)
            }
            val actions = content.extraActions.map { action ->
                NotificationCompat.Action.Builder(action).build().apply { title = action.title?.toString() }
            }
            return Card(id, phase, content.copy(extraActions = actions,
                heldSummary = if (heldSummary == null) null else content.heldSummary),
                callerUri, caller?.hostContactID, avatarKey = content.avatar?.let {
                    val icon = IconCompat.createFromIcon(context, it) ?: return@let AvatarKey.Instance(it)
                    when (icon.type) {
                        IconCompat.TYPE_RESOURCE -> AvatarKey.Resource(icon.resPackage, icon.resId)
                        IconCompat.TYPE_URI, IconCompat.TYPE_URI_ADAPTIVE_BITMAP -> AvatarKey.ContentUri(icon.uri)
                        else -> AvatarKey.Instance(it)
                    }
                })
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
                .setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true).setOnlyAlertOnce(!ringing)
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
    override fun onCreate() { super.onCreate(); instance = this }
    private fun postForeground(id: Int, notification: Notification) {
        ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        postedForeground = notification
    }
    private fun stopPresentation() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foregroundStartPending = false
        val notification = foreground
        val card = foregroundCard
        if (notification == null || card == null) {
            postForeground(FOREGROUND_ID, placeholder(this))
            stopPresentation()
            return START_NOT_STICKY
        }
        if (card.phase == CallPhase.RINGING) {
            val canAlert = ringingSchedule.delayMillis(SystemClock.elapsedRealtimeNanos()) == 0L
            val sequence = ++nextRingingPost
            notification.extras.putLong(RINGING_POST_SEQUENCE, sequence)
            postForeground(checkNotNull(foregroundId), notification)
            observeRingingPost(getSystemService(NotificationManager::class.java), checkNotNull(foregroundId), sequence)
            if (canAlert) lastAlertedCards[card.id] = NotificationCard(checkNotNull(foregroundId), card)
            else {
                lastAlertedCards.remove(card.id)
                requestRingingPost(this, card.id, allowCreate = false)
            }
        } else postForeground(checkNotNull(foregroundId), notification)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
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
