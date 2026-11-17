package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationUiNavigationTest {
    @get:Rule val uiArtifacts: org.junit.rules.TestRule = NotificationUiArtifactsRule()
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun aCardPostedAfterShadeOpeningCanStillBeTappedWithinTheOriginalWait() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val person = "delayed-notification-${System.nanoTime()}"
        val action = "${context.packageName}.$person"
        val received = AtomicBoolean()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received.set(true) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        manager.createNotificationChannel(NotificationChannel(person, "Navigation control", NotificationManager.IMPORTANCE_LOW))
        val tap = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        try {
            withTimeout(5000) { prepareNotificationTestDisplay() }
            // The initial lookup sees the opening shade before this row exists.
            val posting = launch {
                delay(1200)
                manager.notify(1986, Notification.Builder(context, person)
                    .setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle(person).setContentIntent(tap).build())
            }
            withTimeout(5000) {
                tapNotificationTestCard(person)
                while (!received.get()) delay(10)
            }
            posting.join()
            assertTrue("The delayed row must deliver its real SystemUI content intent", received.get())
        } finally {
            manager.cancel(1986)
            manager.deleteNotificationChannel(person)
            tap.cancel()
            context.unregisterReceiver(receiver)
            withTimeout(5000) { collapseNotificationTestShade() }
        }
    }
}
