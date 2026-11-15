package expo.modules.callkittelecom.conversations

import android.Manifest
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PushFaultJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test fun shellActivityFaultsUseRealIntakeAndPreserveEmptyTokensAcrossBridgeReplacement() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia
            val previousOwner = engine.bridgeOwner
            val previousSender = engine.emit
            val previousToken = (engine.pushTokenSnapshot()["token"] as? Map<*, *>)?.get("value") as? String
            val prefix = "push-fault-${System.nanoTime()}"
            val ids = listOf("$prefix-delay", "$prefix-drop", "$prefix-duplicate")
            val signals = mutableListOf<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId in ids) signals += it }
            fun command(name: String) = Intent(context, PushFaultActivity::class.java).putExtra("command", name)
            fun events(): List<JSONObject> {
                val array = JSONObject(File(context.filesDir, "push-fault-events.json").readText()).getJSONArray("events")
                return (0 until array.length()).map(array::getJSONObject)
            }
            fun state(id: String) = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>()
                .firstOrNull { it["id"] == id }
            suspend fun settled(id: String, phase: String): JSONObject = withTimeout(5000) {
                while (true) {
                    events().firstOrNull { it.optString("deliveryId") == id && it.optString("phase") == phase }?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            fun launch(name: String, call: String, delivery: String, delayMillis: Long) {
                context.startActivity(command(name).putExtra("callId", call).putExtra("deliveryId", delivery)
                    .putExtra("eventId", "$call-invite").putExtra("delayMillis", delayMillis)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            suspend fun end(id: String) {
                if (state(id) != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(5000) { while (state(id) != null || TelecomEvidence.calls().isNotEmpty()) delay(10) }
            }
            NativeMediaSignals.observers += observer
            try {
                engine.nativeMedia = AndroidNativeMedia.NONE
                CallPushIntake.configure()
                PushFaultRuntime.command(context, command("reset"))
                launch("drop", ids[1], "$prefix-dropped", 50)
                settled("$prefix-dropped", "dropped")
                assertNull(state(ids[1]))
                assertTrue(signals.none { it.serverCallId == ids[1] })
                assertTrue(TelecomEvidence.calls().isEmpty())

                launch("deliver", ids[0], "$prefix-delayed", 250)
                val delivered = settled("$prefix-delayed", "recognized")
                val scheduled = events().single { it.optString("deliveryId") == "$prefix-delayed" && it.optString("phase") == "scheduled" }
                assertTrue(delivered.getString("elapsedRealtimeNanos").toLong() -
                    scheduled.getString("elapsedRealtimeNanos").toLong() >= 250_000_000L)
                withTimeout(5000) { while (state(ids[0])?.get("status") != "ringing" ||
                    signals.none { it.serverCallId == ids[0] && it.stage == NativeMediaStage.CORE_ADMITTED }) delay(10) }
                assertEquals(1, TelecomEvidence.calls().size)
                end(ids[0])

                launch("deliver", ids[2], "$prefix-first", 0)
                settled("$prefix-first", "recognized")
                withTimeout(5000) { while (state(ids[2])?.get("status") != "ringing") delay(10) }
                launch("deliver", ids[2], "$prefix-second", 0)
                settled("$prefix-second", "recognized")
                withTimeout(5000) { while (signals.count { it.serverCallId == ids[2] && it.stage == NativeMediaStage.PUSH_RECEIVED } != 2 ||
                    signals.none { it.serverCallId == ids[2] && it.stage == NativeMediaStage.CORE_ADMITTED }) delay(10) }
                assertEquals(1, signals.count { it.serverCallId == ids[2] && it.stage == NativeMediaStage.CORE_ADD_REQUESTED })
                assertEquals(1, signals.count { it.serverCallId == ids[2] && it.stage == NativeMediaStage.CORE_ADMITTED })
                assertEquals(1, TelecomEvidence.calls().size)
                val deliveries = events().filter { it.optString("callId") == ids[2] && it.optString("phase") == "recognized" }
                assertEquals(setOf("$prefix-first", "$prefix-second"), deliveries.map { it.getString("deliveryId") }.toSet())
                assertEquals(1, deliveries.map { it.getString("eventId") }.distinct().size)
                end(ids[2])

                engine.bind(Any()) { _, _ -> }
                engine.updatePushToken("fixture-restored-token")
                val owner = engine.bridgeOwner
                PushFaultRuntime.command(context, command("rebind-empty-token").putExtra("deliveryId", "$prefix-rebind"))
                val tokens = events().filter { it.optString("kind") == "tokenRebind" }
                assertEquals(setOf("absent", "empty"), tokens.map { it.getString("tokenState") }.toSet())
                for (token in tokens) {
                    assertTrue(token.getBoolean("sameSnapshot"))
                    assertEquals(token.getLong("revisionBefore"), token.getLong("revisionAfter"))
                    val empty = token.getString("tokenState") == "empty"
                    assertEquals(empty, token.getBoolean("tokenPresent")); assertEquals(empty, token.getBoolean("tokenEmpty"))
                    assertFalse(token.has("value")); assertFalse(token.has("token"))
                }
                assertSame(owner, engine.bridgeOwner)
                assertEquals("fixture-restored-token", (engine.pushTokenSnapshot()["token"] as Map<*, *>)["value"])
                assertFalse(File(context.filesDir, "push-fault-events.json").readText().contains("fixture-restored-token"))
            } finally { withContext(NonCancellable) {
                try {
                    PushFaultRuntime.cancelPending()
                    CallPushIntake.configure()
                    for (id in ids) if (state(id) != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                    withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(10) }
                } finally {
                    engine.updatePushToken(previousToken)
                    engine.bind(previousOwner, previousSender)
                    engine.nativeMedia = previousMedia
                    NativeMediaSignals.observers -= observer
                }
            }
            }
        }
    }
}
