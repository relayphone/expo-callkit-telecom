package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import expo.modules.callkittelecom.R
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationNotificationEligibilityTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun aFilteredIncomingRecordIsAcceptedWithoutForegroundOwnership() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(NotificationManager::class.java)
            val id = "filtered-record-${System.nanoTime()}"
            for (status in listOf("ringing", "connecting")) {
                assertTrue("The real $status record post must be accepted, not converted to a refusal",
                    ConversationService.recordFiltered(context, mapOf("id" to id, "status" to status,
                        "payload" to mapOf("caller" to mapOf("handleType" to "phone", "value" to "+15550001006",
                            "displayName" to id)))))
            }
            withTimeout(5000) {
                while (manager.activeNotifications.any {
                    it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == id
                }) delay(10)
            }
        }
    }

    @Test fun anOutgoingCallCanBePresentedWhileAnEarlierCallRetainsItsEndingCard() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(NotificationManager::class.java)
            val first = "ending-primary-${System.nanoTime()}"
            val second = "outgoing-secondary-${System.nanoTime()}"
            var secondaryTitle = second
            suspend fun posted(name: String, accepts: (Notification) -> Boolean = { true }): Notification = withTimeout(5000) {
                while (true) {
                    val card = manager.activeNotifications.firstOrNull {
                        (it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == name ||
                            it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == name) && accepts(it.notification)
                    }
                    if (card != null) return@withTimeout card.notification
                    delay(10)
                }
                error("unreachable")
            }
            ConversationPresentation.configure { state, _ ->
                CallNotificationContent(title = if (state["id"] == second) secondaryTitle else state["id"] as String,
                    endingTitle = "$first ending")
            }
            try {
                ConversationService.show(context, first, mapOf("status" to "connected"))
                posted(first) { it.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
                ConversationService.ending(context, first)
                posted("$first ending")
                // A real disconnect may retain the ending primary while another call is admitted.
                ConversationService.show(context, second, mapOf("status" to "dialing"))
                val secondary = posted(second)
                assertEquals(0, secondary.flags and Notification.FLAG_FOREGROUND_SERVICE)
                assertNull(secondary.fullScreenIntent)
                assertNotEquals(Notification.CallStyle::class.java.name,
                    secondary.extras.getString(Notification.EXTRA_TEMPLATE))
                assertTrue(secondary.actions.any { it.title.toString() == context.getString(R.string.expo_callkit_telecom_hang_up) })
                secondaryTitle = "$second refreshed"
                ConversationPresentation.refresh(context)
                assertNotEquals(Notification.CallStyle::class.java.name,
                    posted(secondaryTitle).extras.getString(Notification.EXTRA_TEMPLATE))
                ConversationService.remove(context, first)
                assertEquals(Notification.CallStyle::class.java.name, posted(secondaryTitle) {
                    it.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                }.extras.getString(Notification.EXTRA_TEMPLATE))
            } finally {
                ConversationPresentation.configure(null)
                ConversationService.remove(context, second)
                ConversationService.remove(context, first)
            }
        }
    }
}
