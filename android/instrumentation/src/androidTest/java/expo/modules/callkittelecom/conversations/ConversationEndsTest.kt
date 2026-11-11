package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.os.SystemClock
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
class ConversationEndsTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = ConversationEngine.get(context)
        val id = "reported-end-${System.nanoTime()}"
        private val previousMedia = engine.nativeMedia
        private val previousPolicy = engine.policy
        private val previousEmit = engine.emit
        var terminal: Map<*, *>? = null
        var terminalSnapshots = 0
        var elsewhereEvents = 0

        init {
            engine.nativeMedia = AndroidNativeMedia.NONE
            engine.policy = JSONObject(mapOf("prewarm" to false))
            engine.emit = { name, body ->
                if (name == "conversationsChanged") {
                    (body["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull {
                        it["id"] == id && it["status"] == "ended"
                    }?.let { terminal = it["ended"] as Map<*, *>; terminalSnapshots++ }
                }
                if (name == "conversationEvent" && body["type"] == "answeredElsewhere" &&
                    (body["payload"] as Map<*, *>)["serverCallId"] == id) elsewhereEvents++
            }
        }

        fun state() = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
        fun notificationPresent() = context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.category == Notification.CATEGORY_CALL &&
                it.notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name?.toString() == id
        }
        suspend fun incoming() = engine.incoming(IncomingCallPush(id, "incoming-$id",
            mapOf("handleType" to "generic", "value" to id, "displayName" to id)).payload())
        suspend fun removed(timeoutMillis: Long = 5000) = withTimeout(timeoutMillis) {
            while (state() != null || notificationPresent() || TelecomEvidence.calls().isNotEmpty()) delay(10)
        }
        suspend fun close() {
            try {
                if (state() != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                removed()
            } finally {
                engine.emit = previousEmit; engine.policy = previousPolicy; engine.nativeMedia = previousMedia
            }
        }
    }

    private fun fixture(body: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main) { val fixture = Fixture(); try { fixture.body() } finally { fixture.close() } }
    }

    @Test fun appReportedAnsweredElsewhereRemovesRingingTelecomCallAndNotificationWithinTwoSeconds() = fixture {
        assertEquals("reported", incoming()["status"])
        assertEquals("ringing", state()?.get("status"))
        withTimeout(5000) { while (!notificationPresent()) delay(10) }
        assertTrue(TelecomEvidence.calls().single().contains("state=RINGING"))
        val reportedAt = SystemClock.elapsedRealtimeNanos()
        withContext(Dispatchers.Default) { ConversationEnds.report(context, id, "answeredElsewhere") }
        removed(2000)
        assertTrue("All ringing surfaces must disappear within 2 seconds of the app report",
            SystemClock.elapsedRealtimeNanos() - reportedAt <= 2_000_000_000L)
        assertEquals("answeredElsewhere", terminal?.get("reason"))
        assertEquals(1, terminalSnapshots); assertEquals(1, elsewhereEvents)
        // A later push and repeated app report must not recreate surfaces or terminal events.
        engine.remoteEnd(EndedCallPush(id, "later-push-$id", "answeredElsewhere").payload())
        ConversationEnds.report(context, id, "answeredElsewhere")
        assertNull(state()); assertFalse(notificationPresent()); assertTrue(TelecomEvidence.calls().isEmpty())
        assertEquals(1, terminalSnapshots); assertEquals(1, elsewhereEvents)
    }

    @Test fun appReportedEndBeforeCoreAdmissionIsRetainedUntilTheCallIsReported() = fixture {
        var reportedAt = 0L
        val admittedAt = CompletableDeferred<Long>()
        val observer = NativeMediaObserver { signal -> if (signal.serverCallId == id) when (signal.stage) {
            NativeMediaStage.CORE_ADD_REQUESTED -> {
                ConversationEnds.report(context, id, "answeredElsewhere")
                reportedAt = SystemClock.elapsedRealtimeNanos()
            }
            NativeMediaStage.CORE_ADMITTED -> admittedAt.complete(signal.elapsedRealtimeNanos)
            else -> Unit
        } }
        NativeMediaSignals.observers += observer
        try {
            assertEquals("reported", incoming()["status"])
            val admission = withTimeout(3000) { admittedAt.await() }
            assertTrue("The report must finish before actual Core admission", reportedAt > 0 && reportedAt < admission)
            removed()
            assertEquals("answeredElsewhere", terminal?.get("reason"))
            assertEquals(1, terminalSnapshots); assertEquals(1, elsewhereEvents)
        } finally { NativeMediaSignals.observers -= observer }
    }
}
