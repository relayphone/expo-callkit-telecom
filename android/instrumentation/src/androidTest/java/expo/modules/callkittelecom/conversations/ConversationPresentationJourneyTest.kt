package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.os.Bundle
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

    private data class Attention(val sound: Boolean, val vibration: Boolean)

    private suspend fun attention(key: String): Attention = withContext(Dispatchers.IO) {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys -t 5 notification")
        val lines = ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readLines() }.map { it.trim() }
        check(lines.any { it.startsWith("mSoundNotificationKey=") } && lines.any { it.startsWith("mVibrateNotificationKey=") }) {
            "Missing platform notification attention evidence"
        }
        Attention("mSoundNotificationKey=$key" in lines, "mVibrateNotificationKey=$key" in lines)
    }

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
        val emissions = mutableListOf<String>()
        var ended: Map<*, *>? = null
        var stage = "fixture setup"

        init {
            engine.actions.resetBridge()
            engine.nativeMedia = AndroidNativeMedia.NONE
            engine.policy = JSONObject(mapOf("prewarm" to false))
            engine.emit = { name, body ->
                emissions += name
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
        @Suppress("DEPRECATION")
        fun service() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == ConversationService::class.java.name }
        suspend fun posted(notificationId: Int, matches: (Notification) -> Boolean = { true }): StatusBarNotification = withTimeout(5000) {
            while (true) {
                val card = cards().singleOrNull { it.id == notificationId }
                if (card != null && matches(card.notification)) return@withTimeout card
                delay(10)
            }
            error("unreachable")
        }
        suspend fun refreshPresentation() = withContext(Dispatchers.Default) {
            ConversationPresentation.refresh(context)
            withContext(Dispatchers.Main) { }
        }
        private suspend fun shell(command: String) = withContext(Dispatchers.IO) {
            val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
            Unit
        }
        suspend fun observePosts(body: suspend () -> Unit) {
            val component = ComponentName(context, DndObservationService::class.java)
            val alreadyAllowed = manager.isNotificationListenerAccessGranted(component)
            if (!alreadyAllowed) shell("cmd notification allow_listener ${component.flattenToString()}")
            try {
                withTimeout(5000) { while (!DndObservationService.connected) delay(10) }
                DndObservationService.posts.clear()
                body()
            } finally {
                if (!alreadyAllowed) shell("cmd notification disallow_listener ${component.flattenToString()}")
                DndObservationService.posts.clear()
            }
        }
        fun alerts(notificationId: Int) = DndObservationService.posts.filter {
            it.id == notificationId && it.notification.flags and Notification.FLAG_INSISTENT != 0 &&
                it.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0
        }
        suspend fun screenOff() {
            shell("input keyevent 223")
            withTimeout(5000) { while (context.getSystemService(PowerManager::class.java).isInteractive) delay(10) }
        }
        suspend fun wakeScreen() { shell("input keyevent 224"); shell("input keyevent 82") }
        suspend fun withFullScreenPermission(body: suspend () -> Unit) {
            val alreadyAllowed = manager.canUseFullScreenIntent()
            if (!alreadyAllowed) shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
            try { body() }
            finally { if (!alreadyAllowed) shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT default") }
        }
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

    @Test fun refreshUpdatesRingingContentOnTheSameIdAndRemainsInsistent() = fixture {
        stage = "ringing refresh setup"
        var title = id
        var extraAction = "Assistant"
        ConversationPresentation.configure { _, _ ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            CallNotificationContent(title = title,
                extraActions = listOf(NotificationCompat.Action(0, extraAction, activityIntent(extraAction))))
        }
        incoming()
        stage = "original ringing card"
        val original = card()
        assertNotNull(original.notification.fullScreenIntent)
        title = "Resolved caller"; extraAction = "Listen"
        val snapshot = engine.snapshot
        val beforeEmissions = emissions.toList()
        refreshPresentation()
        stage = "waiting for refreshed ringing content"
        val updated = posted(original.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == title &&
            it.actions.any { action -> action.title == "Listen" } }
        assertEquals(original.id, updated.id)
        assertEquals(0, updated.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertTrue(updated.notification.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(original.notification.fullScreenIntent, updated.notification.fullScreenIntent)
        assertFalse(updated.notification.actions.any { it.title == "Assistant" })
        assertSame(snapshot, engine.snapshot)
        assertEquals(beforeEmissions, emissions)
    }

    @Test fun refreshUpdatesOngoingContentWithoutMovingForegroundOwnership() = fixture {
        var title = id
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = title) }
        outgoing(id)
        engine.report(id, mapOf("type" to "connected"))
        val original = card()
        withTimeout(5000) { while (service()?.foreground != true) delay(10) }
        val originalService = service()!!.service
        title = "Resolved ongoing caller"
        val snapshot = engine.snapshot
        ConversationPresentation.refresh(context)
        stage = "refreshed ongoing card"
        val updated = posted(original.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == title }
        assertEquals(original.id, updated.id)
        assertTrue(updated.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(updated.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Attention(false, false), attention(updated.key))
        assertEquals(originalService, service()?.service)
        assertTrue(service()?.foreground == true)
        assertSame(snapshot, engine.snapshot)
    }

    @Test fun refreshRestartsRingtoneAndVibrationAcrossRepeatedUpdates() = fixture {
        val audio = context.getSystemService(AudioManager::class.java)
        val previousRinger = audio.ringerMode
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_RING)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, audio.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
        val sound = Uri.parse("android.resource://${context.packageName}/raw/refresh_ringtone")
        manager.createNotificationChannel(NotificationChannel(channelId, "Refresh ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 200, 200, 200)
        })
        var title = id
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = title, channelId = channelId) }
        // Isolate presentation from Telecom's audio focus while exercising real Android feedback.
        ConversationService.show(context, id, mapOf("id" to id, "status" to "ringing"))
        try {
            notification()
            withTimeout(5000) { while (service()?.foreground != true) delay(10) }
            val original = cards().single()
            withTimeout(5000) { while (true) {
                val active = attention(original.key)
                stage = "waiting for active ringing feedback: $active, audioMode=${audio.mode}, ringer=${audio.ringerMode}, volume=${audio.getStreamVolume(AudioManager.STREAM_RING)}"
                if (active == Attention(true, true)) break
                delay(25)
            } }
            repeat(2) { index ->
                title = "Resolved ringing $index"
                refreshPresentation()
                stage = "ringing feedback after refresh $index"
                val updated = posted(original.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == title }
                assertEquals(0, updated.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
                assertTrue(updated.notification.flags and Notification.FLAG_INSISTENT != 0)
                withTimeout(750) { while (attention(original.key) != Attention(true, true)) delay(10) }
            }
            ConversationService.remove(context, id)
            removed()
            assertEquals(Attention(false, false), attention(original.key))
        } finally {
            ConversationService.remove(context, id)
            audio.setStreamVolume(AudioManager.STREAM_RING, previousVolume, 0)
            audio.ringerMode = previousRinger
        }
    }

    @Test fun refreshUpdatesBothWaitingCardsWithoutChangingTheirIds() = fixture {
        val titles = mutableMapOf<String, String>()
        val customized = mutableListOf<String>()
        ConversationPresentation.configure { conversation, _ ->
            val callId = conversation["id"] as String
            customized += callId
            CallNotificationContent(title = titles[callId] ?: callId)
        }
        outgoing(id)
        engine.report(id, mapOf("type" to "connected"))
        val waitingId = "$id-waiting"
        incoming(waitingId)
        val primary = card(id, 2)
        val waiting = card(waitingId, 2)
        titles[id] = "Resolved ongoing"; titles[waitingId] = "Resolved waiting"
        customized.clear()
        val snapshot = engine.snapshot
        refreshPresentation()
        stage = "refreshed waiting cards"
        posted(primary.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == titles[id] }
        val updatedWaiting = posted(waiting.id) {
            it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == titles[waitingId]
        }
        assertEquals(setOf(primary.id, waiting.id), cards().map { it.id }.toSet())
        assertEquals(setOf(id, waitingId), customized.toSet())
        assertEquals(2, customized.size)
        assertTrue(service()?.foreground == true)
        assertEquals(0, updatedWaiting.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertTrue(updatedWaiting.notification.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(waiting.notification.fullScreenIntent, updatedWaiting.notification.fullScreenIntent)
        assertSame(snapshot, engine.snapshot)
    }

    @Test fun otherCallSnapshotsDoNotRepostUnchangedWaitingContent() = fixture {
        ConversationPresentation.configure { conversation, _ -> CallNotificationContent(
            title = conversation["id"] as String,
            avatar = Icon.createWithResource(context, android.R.drawable.sym_def_app_icon),
            extraActions = listOf(NotificationCompat.Action(0, "Assistant", activityIntent("assistant")))) }
        outgoing(id)
        engine.report(id, mapOf("type" to "connected"))
        card()
        observePosts {
            val waitingId = "$id-waiting"
            incoming(waitingId)
            val original = card(waitingId, 2)
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            engine.mute(id, true)
            engine.mute(id, false)
            delay(1400)
            assertEquals("Other calls must not restart this ring", 1, alerts(original.id).size)
            assertEquals(original.postTime, posted(original.id).postTime)
        }
    }

    @Test fun anotherRingingCardDoesNotRepostTheExistingRingingCard() = fixture {
        ConversationPresentation.configure { conversation, _ -> CallNotificationContent(title = conversation["id"] as String,
            avatar = Icon.createWithResource(context, android.R.drawable.sym_def_app_icon),
            extraActions = listOf(NotificationCompat.Action(0, "Assistant", activityIntent("assistant")))) }
        val secondId = "$id-second"
        try { observePosts {
            ConversationService.show(context, id, mapOf("status" to "ringing"))
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            // Exercise the real presentation roster independently of Telecom's admission limit.
            ConversationService.show(context, secondId, mapOf("status" to "ringing"))
            card(secondId, 2)
            delay(1400)
            assertEquals(1, alerts(original.id).size)
            assertEquals(original.postTime, posted(original.id).postTime)
        } } finally {
            ConversationService.remove(context, secondId)
            ConversationService.remove(context, id)
        }
    }

    @Test fun refreshWithEquivalentActionsAndResourceAvatarPostsNothing() = fixture {
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = id,
            avatar = Icon.createWithResource(context, android.R.drawable.sym_def_app_icon),
            extraActions = listOf(NotificationCompat.Action(0, "Assistant", activityIntent("assistant")))) }
        observePosts {
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            repeat(2) { refreshPresentation(); delay(200) }
            delay(1200)
            assertEquals(1, alerts(original.id).size)
            assertEquals(original.postTime, posted(original.id).postTime)
        }
    }

    @Test fun changedTitleRefreshAlertsExactlyOnceAndAnEquivalentRefreshDoesNot() = fixture {
        var title = id
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = title) }
        observePosts {
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            title = "Resolved caller"
            refreshPresentation()
            posted(original.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == title }
            refreshPresentation()
            delay(1400)
            assertEquals(2, alerts(original.id).size)
            assertEquals("Resolved caller", alerts(original.id).last().notification.extras
                .getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name)
        }
    }

    @Test fun ringingRefreshComparesActionAndScreenIntentsChannelsAndUriAvatars() = fixture {
        val secondChannel = "$channelId-second"
        for (channel in listOf(channelId, secondChannel)) manager.createNotificationChannel(
            NotificationChannel(channel, "App ringing", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        var action = activityIntent("assistant")
        var actionTitle = "Assistant"
        var screen = activityIntent("screen")
        var channel = channelId
        var avatar: Icon? = null
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = id, avatar = avatar,
            extraActions = listOf(NotificationCompat.Action(0, actionTitle, action)),
            fullScreenIntent = screen, channelId = channel) }
        try { observePosts {
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            suspend fun change(update: () -> Unit) {
                val before = alerts(original.id).size
                update(); refreshPresentation()
                withTimeout(5000) { while (alerts(original.id).size == before) delay(10) }
                assertEquals(before + 1, alerts(original.id).size)
            }
            change { action = activityIntent("listen") }
            change { actionTitle = "Listen" }
            change { screen = activityIntent("updated-screen") }
            change { channel = secondChannel }
            val uri = Uri.parse("android.resource://android/${android.R.drawable.sym_def_app_icon}")
            change { avatar = Icon.createWithContentUri(uri) }
            avatar = Icon.createWithContentUri(uri)
            refreshPresentation()
            delay(1400)
            assertEquals(6, alerts(original.id).size)
            val updated = posted(original.id).notification
            assertEquals(secondChannel, updated.channelId); assertEquals(screen, updated.fullScreenIntent)
            assertEquals(action, updated.actions.single { it.title == "Listen" }.actionIntent)
        } } finally {
            engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
            removed()
            withTimeout(5000) { while (service() != null) delay(10) }
            manager.deleteNotificationChannel(secondChannel)
        }
    }

    @Test fun bitmapAvatarReplacementRequiresRefreshAndTheSameIconDoesNotRepost() = fixture {
        fun bitmapIcon() = Icon.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        var avatar = bitmapIcon()
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = id, avatar = avatar) }
        observePosts {
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            avatar = bitmapIcon()
            engine.report(id, mapOf("type" to "updated", "video" to true))
            delay(1400)
            assertEquals(1, alerts(original.id).size)
            refreshPresentation()
            withTimeout(5000) { while (alerts(original.id).size < 2) delay(10) }
            refreshPresentation()
            delay(1400)
            assertEquals(2, alerts(original.id).size)
        }
    }

    @Test fun revertingAQueuedRefreshToThePostedContentDropsTheAlert() = fixture {
        var title = id
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = title) }
        observePosts {
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            title = "Temporary resolution"
            refreshPresentation()
            delay(200)
            title = id
            refreshPresentation()
            delay(1400)
            assertEquals(1, alerts(original.id).size)
            assertEquals(original.postTime, posted(original.id).postTime)
        }
    }

    @Test fun revertedSnapshotsWithNewBitmapIconsKeepThePostedRingingAvatar() = fixture {
        var title = id
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = title,
            avatar = Icon.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))) }
        observePosts {
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            title = "Temporary snapshot title"
            engine.report(id, mapOf("type" to "updated", "video" to true))
            delay(200)
            title = id
            engine.report(id, mapOf("type" to "updated", "video" to false))
            delay(1400)
            assertEquals(1, alerts(original.id).size)
            assertEquals(original.postTime, posted(original.id).postTime)
        }
    }

    @Test fun queuedActionsFreezeMutableTextAndIntentUntilTheNextRefresh() = fixture {
        val label = StringBuilder("Assistant")
        val originalIntent = activityIntent("assistant")
        val action = NotificationCompat.Action(0, label, originalIntent)
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = id, extraActions = listOf(action)) }
        observePosts {
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).isEmpty()) delay(10) }
            label.replace(0, label.length, "Queued action")
            refreshPresentation()
            label.replace(0, label.length, "Latest action")
            val latestIntent = activityIntent("latest")
            action.actionIntent = latestIntent
            val queued = posted(original.id) { it.actions.any { action -> action.title == "Queued action" } }
            assertEquals(originalIntent, queued.notification.actions.single { it.title == "Queued action" }.actionIntent)
            refreshPresentation()
            val latest = posted(original.id) { it.actions.any { action -> action.title == "Latest action" } }
            assertEquals(latestIntent, latest.notification.actions.single { it.title == "Latest action" }.actionIntent)
            refreshPresentation()
            delay(1400)
            assertEquals(3, alerts(original.id).size)
        }
    }

    @Test fun twoRingingRefreshesMergeIntoOneTrailingLatestContentPost() = fixture {
        observePosts {
            var title = id
            ConversationPresentation.configure { _, _ -> CallNotificationContent(title = title) }
            incoming()
            val original = card()
            withTimeout(5000) { while (alerts(original.id).none { it.postTime == original.postTime }) delay(10) }
            title = "First refresh"
            refreshPresentation()
            delay(200)
            title = "Latest refresh"
            refreshPresentation()
            stage = "coalesced ringing refresh"
            val updated = posted(original.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == title }
            withTimeout(5000) { while (alerts(original.id).size < 2) delay(10) }
            delay(1200)
            val posts = alerts(original.id)
            assertEquals("Two close requests must produce one trailing alert", 2, posts.size)
            assertTrue("The initial alert participates in the cooldown: ${posts.map { it.postTime }}",
                posts[1].postTime - posts[0].postTime >= 1100)
            assertEquals("Latest refresh", posts[1].notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name)
            assertEquals(original.id, updated.id)
            assertEquals(original.notification.channelId, updated.notification.channelId)
            assertTrue(service()?.foreground == true)
        }
    }

    @Test fun leavingRingingCancelsThePendingTrailingAlert() = fixture {
        observePosts {
            val titles = mutableMapOf<String, String>()
            ConversationPresentation.configure { conversation, _ ->
                val callId = conversation["id"] as String
                CallNotificationContent(title = titles[callId] ?: callId)
            }
            for (outcome in listOf("answered", "ended", "declined")) {
                val callId = "$id-$outcome"
                incoming(callId)
                val original = card(callId)
                withTimeout(5000) { while (alerts(original.id).none { it.postTime == original.postTime }) delay(10) }
                val before = alerts(original.id).size
                titles[callId] = "Pending $outcome"
                refreshPresentation()
                delay(200)
                when (outcome) {
                    "answered" -> {
                        engine.join(callId)
                        engine.report(callId, mapOf("type" to "connected"))
                        posted(original.id) { it.flags and Notification.FLAG_INSISTENT == 0 }
                    }
                    "ended" -> engine.report(callId, mapOf("type" to "ended", "reason" to "remoteEnded"))
                    else -> engine.end(callId)
                }
                delay(1200)
                assertEquals("A pending alert must be dropped after $outcome", before, alerts(original.id).size)
                if (outcome == "answered") engine.report(callId, mapOf("type" to "ended", "reason" to "remoteEnded"))
                removed()
            }
        }
    }

    @Test fun observeFullScreenDeliveryForAnAlertingRefreshWithTheScreenOff() = fixture {
        withFullScreenPermission {
            PresentationRingingActivity.reset()
            val screen = PendingIntent.getActivity(context, 0,
                Intent(context, PresentationRingingActivity::class.java).setAction(id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            var title = id
            ConversationPresentation.configure { _, _ -> CallNotificationContent(title = title, fullScreenIntent = screen) }
            try {
                screenOff()
                incoming()
                val original = card()
                stage = "initial full-screen delivery"
                withTimeout(5000) { while (PresentationRingingActivity.deliveries == 0) delay(25) }
                val before = PresentationRingingActivity.deliveries
                screenOff()
                title = "Resolved screen-off caller"
                refreshPresentation()
                stage = "screen-off alerting refresh"
                val updated = posted(original.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == title }
                delay(1500)
                val after = PresentationRingingActivity.deliveries
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("fsiObservation", "initial=$before,refreshDeliveries=${after - before},screenOffBeforeRefresh=true")
                })
                assertEquals(original.notification.fullScreenIntent, updated.notification.fullScreenIntent)
                assertEquals(0, updated.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
                assertTrue(updated.notification.flags and Notification.FLAG_INSISTENT != 0)
            } finally {
                PresentationRingingActivity.close()
                wakeScreen()
            }
        }
    }

    @Test fun refreshCustomizesUnpresentedHeldCallsWithoutCreatingCardsForThem() = fixture {
        outgoing(id)
        engine.report(id, mapOf("type" to "connected")); engine.hold(id, true)
        val activeId = "$id-active"
        outgoing(activeId)
        engine.report(activeId, mapOf("type" to "connected"))
        val original = card(activeId)
        val customized = mutableListOf<String>()
        ConversationPresentation.configure { conversation, _ ->
            val callId = conversation["id"] as String
            customized += callId
            CallNotificationContent(title = "$callId updated")
        }
        refreshPresentation()
        stage = "refresh with an unpresented held call"
        posted(original.id) { it.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == "$activeId updated" }
        assertEquals(setOf(id, activeId), customized.toSet())
        assertEquals(2, customized.size)
        assertEquals(listOf(original.id), cards().map { it.id })
        assertEquals("held", state()?.get("status"))
    }

    @Test fun refreshWithNoCallsAndAfterEndPostsNothing() = fixture {
        var customizations = 0
        ConversationPresentation.configure { _, _ -> customizations++; CallNotificationContent() }
        val emptySnapshot = engine.snapshot
        refreshPresentation()
        assertTrue(cards().isEmpty()); assertEquals(0, customizations)
        assertSame(emptySnapshot, engine.snapshot)
        incoming()
        card()
        engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
        removed()
        withTimeout(5000) { while (service() != null) delay(10) }
        customizations = 0
        val endedSnapshot = engine.snapshot
        refreshPresentation()
        assertTrue(cards().isEmpty()); assertEquals(0, customizations)
        assertNull(service())
        assertSame(endedSnapshot, engine.snapshot)
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
