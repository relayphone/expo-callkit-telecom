package expo.modules.callkittelecom.conversations

import android.app.Notification
import android.app.Person
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import android.app.Activity
import android.content.ComponentName
import android.os.Bundle

/** Platform ranking evidence from the test application, separate from library policy decisions. */
class DndObservationService : NotificationListenerService() {
    companion object {
        data class Observation(val matchesFilter: Boolean, val soundConfigured: Boolean, val category: String?)
        val observations = ConcurrentHashMap<String, Observation>()
        @Volatile var connected = false
    }
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }
    override fun onNotificationPosted(notification: StatusBarNotification, rankingMap: RankingMap) { record(notification, rankingMap) }
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
        observations[name] = Observation(ranking.matchesInterruptionFilter(), channel?.sound != null, value.notification.category)
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
