package expo.modules.callkittelecom.conversations

import android.app.Notification
import android.app.Person
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import android.app.Activity
import android.content.ComponentName
import android.os.Bundle

/** Platform ranking evidence from the test application, separate from library policy decisions. */
class DndObservationService : NotificationListenerService() {
    companion object {
        data class Observation(val matchesFilter: Boolean, val soundConfigured: Boolean, val category: String?,
            val personUri: String? = null, val personKey: String? = null,
            val channelBypass: Boolean? = null, val importance: Int? = null)
        val observations = ConcurrentHashMap<String, Observation>()
        @Volatile var connected = false
        val posts = CopyOnWriteArrayList<StatusBarNotification>()
        internal val notificationChanges = CopyOnWriteArrayList<CallNotificationSample>()
    }
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }
    override fun onNotificationPosted(notification: StatusBarNotification, rankingMap: RankingMap) {
        change(notification, "posted")
        if (notification.packageName == packageName && notification.notification.category == Notification.CATEGORY_CALL) posts += notification
        record(notification, rankingMap)
    }
    override fun onNotificationRemoved(notification: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        change(notification, "removed:$reason")
    }
    private fun change(value: StatusBarNotification, phase: String) {
        if (value.packageName != packageName || value.notification.category != Notification.CATEGORY_CALL) return
        if (notificationChanges.size == 128) notificationChanges.removeAt(0)
        notificationChanges += CallNotificationSample(phase, android.os.SystemClock.elapsedRealtimeNanos(), listOf(
            CallNotificationState(value.key, value.id, value.tag, value.notification.channelId,
                value.notification.flags, value.isOngoing, value.isClearable)))
    }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        activeNotifications?.forEach { record(it, rankingMap) }
    }
    private fun record(value: StatusBarNotification, map: RankingMap) {
        if (value.packageName != packageName || value.notification.category != Notification.CATEGORY_CALL) return
        val person = value.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java) ?: return
        val name = person.name?.toString() ?: return
        val ranking = Ranking()
        if (!map.getRanking(value.key, ranking)) return
        val channel = getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(value.notification.channelId)
        observations[name] = Observation(ranking.matchesInterruptionFilter(), channel?.sound != null,
            value.notification.category, person.uri, person.key, channel?.canBypassDnd(), ranking.importance)
    }
}

/** Keep the listener process alive while separate --no-restart instrumentation cases attach. */
class DndBootstrapActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        NotificationListenerService.requestRebind(ComponentName(this, DndObservationService::class.java))
        finish()
    }
}
