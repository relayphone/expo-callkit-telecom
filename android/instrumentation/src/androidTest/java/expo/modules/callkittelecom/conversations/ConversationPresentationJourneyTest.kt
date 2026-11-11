package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPresentationJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = ConversationEngine.get(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = "presentation-${System.nanoTime()}"
        private val ids = mutableListOf(id)
        val channelId = "app-$id"
        private val previousMedia = engine.nativeMedia
        private val previousPolicy = engine.policy
        private val previousEmit = engine.emit
        private val previousAcceptUnhandled = engine.actions.acceptUnhandled
        val actions = CopyOnWriteArrayList<ConversationAction>()
        val performed = mutableListOf<Map<*, *>>()
        var ended: Map<*, *>? = null
        var stage = "fixture setup"

        init {
            engine.actions.resetBridge()
            engine.nativeMedia = AndroidNativeMedia.NONE
            engine.policy = JSONObject(mapOf("prewarm" to false))
            engine.emit = { name, body ->
                assertNotEquals("handlerAction", name)
                if (name == "conversationEvent" && body["type"] == "actionPerformed") performed += body["payload"] as Map<*, *>
                if (name == "conversationsChanged") (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
                    .firstOrNull { it["id"] == id && it["status"] == "ended" }?.let { ended = it["ended"] as Map<*, *> }
            }
            ConversationActions.configure { actions += it; ConversationActionResult.Proceed() }
        }

        fun state(callId: String = id) = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == callId }
        suspend fun outgoing(callId: String) {
            if (callId !in ids) ids += callId
            engine.actions.acceptUnhandled = true
            assertEquals(callId, engine.start(mapOf("id" to callId, "to" to mapOf("id" to callId, "displayName" to callId))))
        }
        suspend fun incoming(callId: String = id) {
            if (callId !in ids) ids += callId
            assertEquals("reported", engine.incoming(IncomingCallPush(callId, "incoming-$callId",
                mapOf("handleType" to "generic", "value" to callId, "displayName" to callId),
                metadata = mapOf("card" to "app-card"), media = mapOf("token" to "private-grant")).payload())["status"])
        }
        fun activityIntent(name: String) = PendingIntent.getActivity(context, 0,
            Intent(context, ConversationActivity::class.java).setAction("$id-$name"), PendingIntent.FLAG_IMMUTABLE)
        fun cards() = manager.activeNotifications.filter { it.notification.category == Notification.CATEGORY_CALL }
        fun diagnostics() = "stage=$stage, states=${ids.associateWith { state(it)?.get("status") }}, cards=${cards().map {
            listOf(it.id, it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name,
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT), it.notification.flags)
        }}"
        suspend fun card(callId: String = id, count: Int = 1, matches: (Notification) -> Boolean = { true }): StatusBarNotification = withTimeout(5000) {
            while (true) {
                val cards = cards()
                val card = cards.singleOrNull { it.notification.extras
                    .getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == callId }
                if (cards.size == count && card != null && matches(card.notification)) return@withTimeout card
                delay(10)
            }
            error("unreachable")
        }
        suspend fun notification(callId: String = id, matches: (Notification) -> Boolean = { true }): Notification = withTimeout(5000) {
            while (true) {
                val notification = manager.activeNotifications.firstOrNull {
                    val person = it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
                    it.notification.category == Notification.CATEGORY_CALL &&
                        (person?.name == callId || (callId == id && it.notification.channelId == channelId))
                }?.notification
                if (notification != null && matches(notification)) return@withTimeout notification
                delay(10)
            }
            error("unreachable")
        }
        suspend fun removed() = withTimeout(5000) {
            while (ids.any { state(it) != null } || TelecomEvidence.calls().isNotEmpty() || manager.activeNotifications.any {
                it.notification.channelId == channelId || it.notification.extras
                    .getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() in ids
            }) delay(10)
        }
        suspend fun close() {
            try {
                ConversationPresentation.configure(null); ConversationActions.configure(null)
                for (callId in ids) if (state(callId) != null) engine.report(callId, mapOf("type" to "ended", "reason" to "remoteEnded"))
                removed()
            } finally {
                manager.deleteNotificationChannel(channelId)
                engine.emit = previousEmit; engine.policy = previousPolicy; engine.nativeMedia = previousMedia
                engine.actions.acceptUnhandled = previousAcceptUnhandled
            }
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(10) }
            val fixture = Fixture()
            try { fixture.body() }
            catch (error: TimeoutCancellationException) { throw AssertionError(fixture.diagnostics(), error) }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun appContentKeepsCallStyleControlsAndRingingIntentOnlyUntilAnswer() = fixture {
        val channel = NotificationChannel(channelId, "App ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Settings.System.DEFAULT_RINGTONE_URI, null)
            enableVibration(true); vibrationPattern = longArrayOf(0, 80, 80, 80)
        }
        manager.createNotificationChannel(channel)
        val screen = activityIntent("screen")
        val content = activityIntent("content")
        val assistant = activityIntent("assistant")
        val avatar = Icon.createWithResource("android", android.R.drawable.sym_def_app_icon)
        val entries = mutableListOf<Pair<Map<String, Any?>, CallPhase>>()
        ConversationPresentation.configure { conversation, phase ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            entries += conversation to phase
            CallNotificationContent("App caller", avatar, screen, content,
                listOf(NotificationCompat.Action(0, "Assistant", assistant)), channelId)
        }
        incoming()
        val ringing = notification()
        val person = ringing.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)!!
        assertEquals("App caller", person.name); assertNotNull(person.icon)
        assertEquals(screen, ringing.fullScreenIntent); assertEquals(content, ringing.contentIntent)
        assertEquals(channelId, ringing.channelId)
        assertEquals(assistant, ringing.actions.single { it.title == "Assistant" }.actionIntent)
        assertNotNull(ringing.extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java))
        assertNotNull(ringing.extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java))
        assertTrue(ringing.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(state(), entries.last().first)
        assertTrue(entries.all { it.second == CallPhase.RINGING })
        val payload = entries.last().first["payload"] as Map<*, *>
        assertFalse(payload.containsKey("media")); assertEquals(mapOf("card" to "app-card"), payload["metadata"])
        val retained = manager.getNotificationChannel(channelId)
        assertEquals(channel.sound, retained.sound); assertTrue(retained.shouldVibrate())
        assertArrayEquals(channel.vibrationPattern, retained.vibrationPattern)

        withContext(Dispatchers.Default) { ConversationControls.answer(context, id) }
        val ongoing = notification { it.flags and Notification.FLAG_INSISTENT == 0 }
        assertNull(ongoing.fullScreenIntent); assertEquals(content, ongoing.contentIntent)
        assertEquals(listOf(ConversationAction.Answer(id, id, "app")), actions.toList())
        assertEquals(listOf(CallPhase.RINGING, CallPhase.ONGOING), entries.map { it.second }.distinct())
        assertTrue(performed.any { it["origin"] == "app" && (it["action"] as Map<*, *>)["kind"] == "answer" })
        ConversationControls.end(context, id)
        removed()
        assertEquals(ConversationAction.End(id, id, "app"), actions.last())
        assertEquals("local", ended?.get("reason"))
    }

    @Test fun appDeclineRemovesDefaultRingingNotificationAndTelecomCall() = fixture {
        incoming()
        val ringing = notification()
        assertEquals(ConversationService.incomingChannelId(context), ringing.channelId)
        assertTrue(ringing.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull(ringing.fullScreenIntent)
        ConversationControls.decline(context, id)
        removed()
        assertEquals(listOf(ConversationAction.Decline(id, id, "app")), actions.toList())
        assertEquals("local", ended?.get("reason"))
    }

    @Test fun freshOutgoingCallUsesForegroundWithoutFullScreenOrInsistentFlags() = fixture {
        outgoing(id)
        val first = card().notification
        assertNull(first.fullScreenIntent)
        assertTrue(first.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals(0, first.flags and Notification.FLAG_INSISTENT)
        ConversationControls.end(context, id)
        removed()
    }

    @Test fun snapshotUpdatesWithoutContentChangesDoNotRepostThePrimaryCard() = fixture {
        outgoing(id)
        engine.report(id, mapOf("type" to "connected"))
        val original = card()
        val videoUpdates = mutableSetOf<Boolean>()
        ConversationPresentation.configure { conversation, _ ->
            videoUpdates += conversation["isVideo"] as Boolean
            CallNotificationContent()
        }
        repeat(20) { index ->
            engine.report(id, mapOf("type" to "updated", "video" to (index % 2 == 0)))
            delay(25)
        }
        assertEquals(setOf(false, true), videoUpdates)
        assertEquals("Unchanged content must not consume Android's notification update quota", original.postTime, card().postTime)
        ConversationControls.end(context, id)
        removed()
    }

    @Test fun appActionListChangesAreRenderedWhenTheAppReusesTheList() = fixture {
        val extraActions = mutableListOf(NotificationCompat.Action(0, "Assistant", activityIntent("assistant")))
        ConversationPresentation.configure { _, _ -> CallNotificationContent(extraActions = extraActions) }
        outgoing(id)
        card { it.actions.any { action -> action.title == "Assistant" } }
        extraActions[0] = NotificationCompat.Action(0, "Record", activityIntent("record"))
        engine.report(id, mapOf("type" to "updated", "video" to true))
        stage = "reused app action list"
        card { it.actions.any { action -> action.title == "Record" } && it.actions.none { action -> action.title == "Assistant" } }
        extraActions.clear()
        engine.report(id, mapOf("type" to "updated", "video" to false))
        stage = "remove reused app actions"
        card { it.actions.none { action -> action.title == "Record" } }
        ConversationControls.end(context, id)
        removed()
    }

    @Test fun activeAndHeldCallsShareOneCardAndPrimaryEndRestoresHeldOnTheSameId() = fixture {
        stage = "first outgoing call"
        outgoing(id)
        stage = "first foreground card"
        val foregroundId = card().id
        stage = "connect and hold first call"
        engine.report(id, mapOf("type" to "connected")); engine.hold(id, true)
        val secondId = "$id-second"
        stage = "second outgoing call"
        outgoing(secondId)
        engine.report(secondId, mapOf("type" to "connected"))
        stage = "default held summary"
        val second = card(secondId) { it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "1 call on hold" }
        assertEquals(foregroundId, second.id)
        assertTrue(second.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals("held", state()?.get("status"))
        ConversationPresentation.configure { conversation, _ ->
            assertEquals(secondId, conversation["id"])
            CallNotificationContent(heldSummary = "A paused call")
        }
        engine.report(secondId, mapOf("type" to "updated", "video" to true))
        stage = "custom held summary"
        card(secondId) { it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "A paused call" }
        ConversationPresentation.configure(null)
        ConversationControls.end(context, secondId)
        stage = "restore held primary"
        val restored = card { it.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
        assertEquals(foregroundId, restored.id)
        assertEquals("held", state()?.get("status"))
        ConversationControls.end(context, id)
        stage = "final removal"
        removed()
    }

    @Test fun waitingIncomingHasItsOwnNonForegroundCardAndAnswerUsesTheStablePrimaryId() = fixture {
        outgoing(id); engine.report(id, mapOf("type" to "connected"))
        val foregroundId = card().id
        var waitingId = "$id-waiting"
        incoming(waitingId)
        val waiting = card(waitingId, count = 2)
        assertNotEquals(foregroundId, waiting.id)
        assertEquals(0, waiting.notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
        assertTrue(waiting.notification.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull(waiting.notification.fullScreenIntent)
        assertEquals(foregroundId, card(id, count = 2).id)
        ConversationControls.decline(context, waitingId)
        assertEquals(foregroundId, card().id)
        assertEquals("connected", state()?.get("status"))
        waitingId = "$id-next-waiting"
        incoming(waitingId)
        card(waitingId, count = 2)
        withContext(Dispatchers.Default) { ConversationControls.answer(context, waitingId) }
        val answered = card(waitingId) { it.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
        assertEquals(foregroundId, answered.id)
        assertEquals(0, answered.notification.flags and Notification.FLAG_INSISTENT)
        assertNull(answered.notification.fullScreenIntent)
        assertEquals("held", state()?.get("status"))
        assertEquals(ConversationAction.Answer(waitingId, waitingId, "app"), actions.last { it is ConversationAction.Answer })
        ConversationControls.end(context, waitingId)
        assertEquals(foregroundId, card().id)
        ConversationControls.end(context, id)
        removed()
    }
}
