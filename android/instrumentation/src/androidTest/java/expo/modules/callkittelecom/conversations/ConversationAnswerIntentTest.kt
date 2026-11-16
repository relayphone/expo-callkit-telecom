package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationAnswerIntentTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = ConversationEngine.get(context)
    private fun state(id: String) = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
    private suspend fun videoUpgradeCount(): Int = withContext(Dispatchers.IO) {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys -t 5 telecom")
        ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { reader ->
            reader.lineSequence().count { "VIDEO_STATE_CHANGED (CallAttributes.VIDEO_CALL)" in it }
        }
    }
    private suspend fun notification(id: String, ringing: Boolean): Notification = withTimeout(5000) {
        while (true) {
            val card = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull {
                it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == id &&
                    it.notification.extras.getInt("android.callType") == if (ringing) 1 else 2
            }
            if (card != null) return@withTimeout card.notification
            delay(10)
        }
        error("unreachable")
    }
    private fun answerIntent(card: Notification): PendingIntent {
        val answer = checkNotNull(card.extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java))
        assertTrue("CallStyle answer must remain an action", card.actions.any { it.actionIntent == answer })
        return answer
    }
    private suspend fun fixture(video: Boolean = false, body: suspend (String, List<Map<*, *>>) -> Unit) = withContext(Dispatchers.Main) {
        delay(2 * RINGING_ALERT_INTERVAL_MILLIS)
        val previousMedia = engine.nativeMedia
        val previousPolicy = engine.policy
        val previousAccept = engine.actions.acceptUnhandled
        val previousEmit = engine.emit
        val answers = mutableListOf<Map<*, *>>()
        val id = "answer-intent-${System.nanoTime()}"
        try {
            ConversationPresentation.configure(null)
            NotificationLaunchActivity.reset()
            engine.nativeMedia = AndroidNativeMedia.NONE
            engine.policy = JSONObject(mapOf("prewarm" to false))
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            engine.emit = { name, value -> if (name == "conversationEvent" && value["type"] == "actionPerformed") {
                val payload = value["payload"] as Map<*, *>
                if (payload["conversationId"] == id && (payload["action"] as Map<*, *>)["kind"] == "answer") answers += payload
            } }
            assertEquals("reported", engine.incoming(IncomingCallPush(id, "$id-event",
                mapOf("handleType" to "generic", "value" to "caller", "displayName" to id), hasVideo = video).payload())["status"])
            body(id, answers)
        } finally {
            try {
                ConversationPresentation.configure(null)
                if (state(id) != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(10) }
                NotificationLaunchActivity.active?.finish()
                withTimeout(5000) { while (NotificationLaunchActivity.active != null) delay(10) }
            } finally {
                engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                engine.actions.acceptUnhandled = previousAccept
                engine.emit = previousEmit
            }
        }
    }
    private suspend fun send(intent: PendingIntent) {
        // Model SystemUI's privileged notification tap while this test app is on Home.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            intent.send(context, 0, null, null, null, null, options)
        } finally { automation.dropShellPermissionIdentity() }
    }
    private suspend fun home() {
        withContext(Dispatchers.IO) {
            val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        }
        withTimeout(5000) { while (NotificationLaunchActivity.active?.hasWindowFocus() == true) delay(10) }
    }
    private suspend fun foreground(id: String) = withTimeout(5000) {
        while (NotificationLaunchActivity.active?.hasWindowFocus() != true ||
            NotificationLaunchActivity.lastIntent?.getStringExtra(ConversationNotificationIntents.EXTRA_CONVERSATION_ID) != id) delay(10)
    }
    private suspend fun audioAnswer(notification: Boolean) = fixture { id, answers ->
        assertEquals(false, state(id)?.get("isVideo"))
        assertEquals("Incoming platform call must start audio-only", "A",
            Regex("vidst=([^,\\]]+)").find(TelecomEvidence.calls().single())?.groupValues?.get(1))
        withTimeout(5000) { while (engine.audioRoutes()["current"] == null) delay(10) }
        val available = (engine.audioRoutes()["routes"] as List<*>).filterIsInstance<Map<*, *>>()
        if (available.any { it["type"] == "builtInReceiver" }) {
            withTimeout(5000) { while ((engine.audioRoutes()["current"] as? Map<*, *>)?.get("type") != "builtInReceiver") delay(10) }
        }
        val beforeRoute = (engine.audioRoutes()["current"] as Map<*, *>)["type"]
        println("answerRoute initial=$beforeRoute available=${available.map { it["type"] }}")
        val beforeVideoEvents = videoUpgradeCount()
        if (notification) {
            val card = notification(id, true)
            val answer = answerIntent(card)
            assertTrue("Answer must target an Activity directly", answer.isActivity)
            home(); send(answer); foreground(id)
        } else engine.join(id)
        withTimeout(5000) { while (state(id)?.get("status") == "ringing") delay(10) }
        withTimeout(5000) { while (TelecomEvidence.calls().singleOrNull()?.contains("state=ACTIVE") != true) delay(10) }
        assertEquals(false, state(id)?.get("isVideo"))
        val platform = TelecomEvidence.calls().single()
        val videoState = Regex("vidst=([^,\\]]+)").find(platform)?.groupValues?.get(1)
        assertEquals("Platform call must stay audio-only", "A", videoState)
        assertEquals("Answer must not introduce a platform video transition", beforeVideoEvents, videoUpgradeCount())
        assertEquals("Answer must preserve receiver routing", beforeRoute, (engine.audioRoutes()["current"] as Map<*, *>)["type"])
        assertEquals(1, answers.size)
    }
    @Test fun appAudioAnswerStaysAudioOnly() = runBlocking { audioAnswer(false) }
    @Test fun notificationAudioAnswerStaysAudioOnly() = runBlocking { audioAnswer(true) }

    private suspend fun videoAnswer(fromNotification: Boolean) = fixture(video = true) { id, answers ->
        if (fromNotification) {
            val card = notification(id, true)
            println("videoAnswer actionTitles=${card.actions.map { it.title }}")
            home(); send(answerIntent(card))
            foreground(id)
        } else engine.join(id)
        withTimeout(5000) { while (state(id)?.get("status") == "ringing") delay(10) }
        withTimeout(5000) { while (TelecomEvidence.calls().singleOrNull()?.contains("state=ACTIVE") != true) delay(10) }
        assertEquals(true, state(id)?.get("isVideo"))
        val platform = TelecomEvidence.calls().single()
        val videoState = checkNotNull(Regex("vidst=([^,\\]]+)").find(platform)?.groupValues?.get(1))
        assertTrue("Video must still answer as video", 'T' in videoState || 'R' in videoState)
        assertEquals(1, answers.size)
    }
    @Test fun appVideoAnswerRemainsVideo() = runBlocking { videoAnswer(false) }
    @Test fun notificationVideoAnswerRemainsVideo() = runBlocking { videoAnswer(true) }

    @Test fun notificationAnswerOpensTheLauncherAndActivityRecreationDoesNotAnswerTwice() = runBlocking {
        fixture { id, answers ->
            val card = notification(id, true)
            val answer = answerIntent(card)
            assertTrue(answer.isActivity)
            assertTrue(card.actions.single { it.title.toString().equals("Decline", true) }.actionIntent.isBroadcast)
            home(); send(answer); foreground(id)
            withTimeout(5000) { while (answers.isEmpty()) delay(10) }
            assertEquals(1, answers.size)
            assertEquals(1, NotificationLaunchActivity.answers)
            val replay = Intent(checkNotNull(NotificationLaunchActivity.lastIntent))
            assertFalse(ConversationNotificationIntents.receive(context, Intent(replay)))
            val creations = NotificationLaunchActivity.creations
            checkNotNull(NotificationLaunchActivity.active).apply { intent = replay; recreate() }
            withTimeout(5000) { while (NotificationLaunchActivity.creations <= creations) delay(10) }
            foreground(id)
            assertEquals(1, NotificationLaunchActivity.answers)
            assertEquals(1, answers.size)
            assertTrue(runCatching { send(answer) }.exceptionOrNull() is PendingIntent.CanceledException)
        }
    }

    @Test fun incomingAndOngoingBodiesOpenTheLauncherWithoutAnsweringAndHangupStaysBroadcast() = runBlocking {
        fixture { id, answers ->
            val incoming = notification(id, true)
            val openIncoming = checkNotNull(incoming.contentIntent)
            assertTrue(openIncoming.isActivity)
            home(); send(openIncoming); foreground(id)
            assertFalse(checkNotNull(NotificationLaunchActivity.lastIntent).hasExtra(ConversationNotificationIntents.EXTRA_ACTION))
            assertEquals("ringing", state(id)?.get("status"))
            assertTrue(answers.isEmpty())
            engine.join(id)
            val ongoing = notification(id, false)
            assertTrue(ongoing.actions.single { it.title.toString().equals("Hang Up", true) }.actionIntent.isBroadcast)
            val openOngoing = checkNotNull(ongoing.contentIntent)
            assertTrue(openOngoing.isActivity)
            home(); send(openOngoing); foreground(id)
            assertFalse(checkNotNull(NotificationLaunchActivity.lastIntent).hasExtra(ConversationNotificationIntents.EXTRA_ACTION))
            assertEquals(1, answers.size)
            assertEquals(NotificationLaunchActivity::class.java.name, NotificationLaunchActivity.lastClass)
        }
    }

    @Test fun appContentIntentTakesPrecedenceAndAConfiguredActivityCanAnswerNatively() = runBlocking {
        fixture { id, answers ->
            notification(id, true)
            for (name in listOf("CallActivity", ".CallActivity", "${context.packageName}.CallActivity")) {
                home(); send(ConversationNotificationIntents.activity(context, id, activityClass = name)); foreground(id)
                assertEquals(NotificationAlternateActivity::class.java.name, NotificationLaunchActivity.lastClass)
                assertTrue(answers.isEmpty())
            }
            val custom = PendingIntent.getActivity(context, 17, Intent(context, NotificationAlternateActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            ConversationPresentation.configure { _, _ -> CallNotificationContent(contentIntent = custom) }
            ConversationPresentation.refresh(context)
            withTimeout(5000) { while (notification(id, true).contentIntent != custom) delay(10) }
            val answer = ConversationNotificationIntents.activity(context, id, answer = true,
                activityClass = NotificationAlternateActivity::class.java.name)
            home(); send(answer); foreground(id)
            assertEquals(NotificationAlternateActivity::class.java.name, NotificationLaunchActivity.lastClass)
            withTimeout(5000) { while (answers.isEmpty()) delay(10) }
            assertEquals(1, answers.size)
            assertEquals(custom, notification(id, false).contentIntent)
        }
    }

    @Test fun anUntrustedAnswerExtraCannotAnswer() = runBlocking {
        fixture { id, answers ->
            assertFalse(ConversationNotificationIntents.receive(context, Intent()
                .putExtra(ConversationNotificationIntents.EXTRA_CONVERSATION_ID, id)
                .putExtra(ConversationNotificationIntents.EXTRA_ACTION, "answer")))
            assertEquals("ringing", state(id)?.get("status"))
            assertTrue(answers.isEmpty())
        }
    }
}
