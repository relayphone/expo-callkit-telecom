package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.telecom.CallControlScope
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationAnswerIntentTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val testName = TestName()
    private val trace get() = checkNotNull(AnswerJourneyObservation.trace)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = ConversationEngine.get(context)
    private fun state(id: String) = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
    private suspend fun videoUpgradeCount(): Int = withContext(Dispatchers.IO) {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys -t 5 telecom")
        ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { reader ->
            reader.lineSequence().count { "VIDEO_STATE_CHANGED (CallAttributes.VIDEO_CALL)" in it }
        }
    }
    private suspend fun notification(id: String, ringing: Boolean): Notification = await(
        if (ringing) AnswerJourneyWait.INCOMING_NOTIFICATION else AnswerJourneyWait.ONGOING_NOTIFICATION) {
        while (true) {
            val card = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull {
                it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == id &&
                    it.notification.extras.getInt("android.callType") == if (ringing) 1 else 2
            }
            if (card != null) return@await card.notification
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
        val previousAdmission = engine.coreAdmission
        val answers = mutableListOf<Map<*, *>>()
        val id = "answer-intent-${System.nanoTime()}"
        val observation = AnswerJourneyTrace(id, SystemClock::elapsedRealtimeNanos)
        AnswerJourneyObservation.trace = observation
        observation.mark(AnswerJourneyPhase.FIXTURE_STARTED)
        val signals = NativeMediaObserver { signal ->
            if (signal.serverCallId == id && signal.stage == NativeMediaStage.ANSWER_REQUESTED)
                observation.mark(AnswerJourneyPhase.NATIVE_ANSWER_STARTED, atNanos = signal.elapsedRealtimeNanos)
        }
        NativeMediaSignals.observers += signals
        var succeeded = false
        try {
            withPresentationCleanup(body = {
                ConversationPresentation.configure(null)
                NotificationLaunchActivity.reset()
                engine.nativeMedia = AndroidNativeMedia.NONE
                engine.policy = JSONObject(mapOf("prewarm" to false))
                engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
                engine.coreAdmission = CoreCallAdmission { attributes, callbacks, admitted ->
                    previousAdmission.add(attributes, callbacks) {
                        val real = this
                        admitted(object : CallControlScope by real {
                            override suspend fun setActive(): androidx.core.telecom.CallControlResult {
                                observation.mark(AnswerJourneyPhase.CORE_SET_ACTIVE_STARTED)
                                return real.setActive().also { observation.mark(AnswerJourneyPhase.CORE_SET_ACTIVE_RETURNED) }
                            }
                            override suspend fun answer(callType: Int): androidx.core.telecom.CallControlResult {
                                observation.mark(AnswerJourneyPhase.CORE_ANSWER_STARTED)
                                return real.answer(callType).also { observation.mark(AnswerJourneyPhase.CORE_ANSWER_RETURNED) }
                            }
                        })
                    }
                }
                engine.emit = { name, value ->
                    if (name == "conversationsChanged") {
                        val state = (value["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
                            .firstOrNull { it["id"] == id }?.get("status") as? String
                        if (state != null) observation.mark(when (state) {
                            "connecting" -> AnswerJourneyPhase.SDK_SNAPSHOT_CONNECTING
                            "connected" -> AnswerJourneyPhase.SDK_SNAPSHOT_CONNECTED
                            else -> AnswerJourneyPhase.SDK_SNAPSHOT
                        }, state = state)
                    }
                    if (name == "conversationEvent" && value["type"] == "actionPerformed") {
                        val payload = value["payload"] as Map<*, *>
                        if (payload["conversationId"] == id && (payload["action"] as Map<*, *>)["kind"] == "answer") {
                            answers += payload
                            observation.mark(AnswerJourneyPhase.ANSWER_PERFORMED)
                        }
                    }
                }
                assertEquals("reported", engine.incoming(IncomingCallPush(id, "$id-event",
                    mapOf("handleType" to "generic", "value" to "caller", "displayName" to id), hasVideo = video).payload())["status"])
                observation.mark(AnswerJourneyPhase.INCOMING_REPORTED)
                body(id, answers)
                observation.mark(AnswerJourneyPhase.BODY_COMPLETED)
            }, cleanup = {
                observation.mark(AnswerJourneyPhase.CLEANUP_STARTED)
                try {
                    ConversationPresentation.configure(null)
                    if (state(id) != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                    await(AnswerJourneyWait.PLATFORM_REMOVED) { while (platformCalls().isNotEmpty()) delay(10) }
                    NotificationLaunchActivity.active?.finish()
                    await(AnswerJourneyWait.ACTIVITY_DESTROYED) { while (NotificationLaunchActivity.active != null) delay(10) }
                } finally {
                    engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                    engine.actions.acceptUnhandled = previousAccept; engine.emit = previousEmit
                    engine.coreAdmission = previousAdmission
                }
            })
            succeeded = true
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Answer journey timed out: ${observation.json(testName.methodName, false)}", error)
        } finally {
            NativeMediaSignals.observers -= signals
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("answerJourneyPhases", observation.json(testName.methodName, succeeded))
            })
            AnswerJourneyObservation.trace = null
        }
    }
    private suspend fun <T> await(wait: AnswerJourneyWait, body: suspend () -> T): T {
        trace.mark(AnswerJourneyPhase.WAIT_STARTED, wait)
        try {
            return withTimeout(5000) { body() }.also { trace.mark(AnswerJourneyPhase.WAIT_COMPLETED, wait) }
        } catch (error: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                trace.mark(AnswerJourneyPhase.WAIT_CANCELLED, wait)
                throw error
            }
            trace.mark(AnswerJourneyPhase.WAIT_TIMED_OUT, wait)
            throw AssertionError("Answer wait ${wait.name} timed out: ${trace.json(testName.methodName, false)}", error)
        }
    }
    private suspend fun platformCalls(): List<String> {
        trace.mark(AnswerJourneyPhase.TELECOM_READ_STARTED)
        return TelecomEvidence.calls().also { calls ->
            trace.mark(AnswerJourneyPhase.TELECOM_READ_RETURNED)
            if (calls.any { "state=ACTIVE" in it }) trace.mark(AnswerJourneyPhase.TELECOM_ACTIVE)
        }
    }
    private suspend fun send(intent: PendingIntent) {
        // Model SystemUI's privileged notification tap while this test app is on Home.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            trace.mark(AnswerJourneyPhase.PENDING_INTENT_SENT)
            intent.send(context, 0, null, null, null, null, options)
            trace.mark(AnswerJourneyPhase.PENDING_INTENT_RETURNED)
        } finally { automation.dropShellPermissionIdentity() }
    }
    private suspend fun home() {
        trace.mark(AnswerJourneyPhase.HOME_SENT)
        withContext(Dispatchers.IO) {
            val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        }
        await(AnswerJourneyWait.HOME_FOCUS_LOST) { while (NotificationLaunchActivity.active?.hasWindowFocus() == true) delay(10) }
    }
    private suspend fun foreground(id: String) = await(AnswerJourneyWait.ACTIVITY_FOREGROUND) {
        while (NotificationLaunchActivity.active?.hasWindowFocus() != true ||
            NotificationLaunchActivity.lastIntent?.getStringExtra(ConversationNotificationIntents.EXTRA_CONVERSATION_ID) != id) delay(10)
    }
    private suspend fun audioAnswer(notification: Boolean) = fixture { id, answers ->
        assertEquals(false, state(id)?.get("isVideo"))
        assertEquals("Incoming platform call must start audio-only", "A",
            Regex("vidst=([^,\\]]+)").find(platformCalls().single())?.groupValues?.get(1))
        await(AnswerJourneyWait.AUDIO_ROUTE) { while (engine.audioRoutes()["current"] == null) delay(10) }
        val available = (engine.audioRoutes()["routes"] as List<*>).filterIsInstance<Map<*, *>>()
        if (available.any { it["type"] == "builtInReceiver" }) {
            await(AnswerJourneyWait.RECEIVER_ROUTE) { while ((engine.audioRoutes()["current"] as? Map<*, *>)?.get("type") != "builtInReceiver") delay(10) }
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
        await(AnswerJourneyWait.SNAPSHOT_ANSWERED) { while (state(id)?.get("status") == "ringing") delay(10) }
        await(AnswerJourneyWait.TELECOM_ACTIVE) { while (platformCalls().singleOrNull()?.contains("state=ACTIVE") != true) delay(10) }
        assertEquals(false, state(id)?.get("isVideo"))
        val platform = platformCalls().single()
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
        await(AnswerJourneyWait.SNAPSHOT_ANSWERED) { while (state(id)?.get("status") == "ringing") delay(10) }
        await(AnswerJourneyWait.TELECOM_ACTIVE) { while (platformCalls().singleOrNull()?.contains("state=ACTIVE") != true) delay(10) }
        assertEquals(true, state(id)?.get("isVideo"))
        val platform = platformCalls().single()
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
            await(AnswerJourneyWait.ANSWER_PERFORMED) { while (answers.isEmpty()) delay(10) }
            assertEquals(1, answers.size)
            assertEquals(1, NotificationLaunchActivity.answers)
            val replay = Intent(checkNotNull(NotificationLaunchActivity.lastIntent))
            assertFalse(ConversationNotificationIntents.receive(context, Intent(replay)))
            val creations = NotificationLaunchActivity.creations
            checkNotNull(NotificationLaunchActivity.active).apply { intent = replay; recreate() }
            await(AnswerJourneyWait.ACTIVITY_RECREATED) { while (NotificationLaunchActivity.creations <= creations) delay(10) }
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
            await(AnswerJourneyWait.CONTENT_OVERRIDE) { while (notification(id, true).contentIntent != custom) delay(10) }
            val answer = ConversationNotificationIntents.activity(context, id, answer = true,
                activityClass = NotificationAlternateActivity::class.java.name)
            home(); send(answer); foreground(id)
            assertEquals(NotificationAlternateActivity::class.java.name, NotificationLaunchActivity.lastClass)
            await(AnswerJourneyWait.ANSWER_PERFORMED) { while (answers.isEmpty()) delay(10) }
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
