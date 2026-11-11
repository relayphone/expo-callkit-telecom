package expo.modules.callkittelecom.conversations

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallPushIntakeJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test fun endDuringSuspendedAdmissionCannotStartRinging() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val id = "gated-push-${System.nanoTime()}"
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val signals = mutableListOf<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            NativeMediaSignals.observers += observer
            CallPushIntake.configure(CallPushDecoder { data ->
                if (data["kind"] == "end") DecodedCallPush.Ended(EndedCallPush(id, "$id-end", "remoteEnded"))
                else DecodedCallPush.Incoming(IncomingCallPush(id, "$id-invite",
                    mapOf("handleType" to "generic", "value" to "caller", "displayName" to "Gated caller")))
            }, CallPushAdmission { started.complete(Unit); gate.await(); CallPushDecision.Admit })
            try {
                assertTrue(CallPushIntake.handle(context, RemoteMessage.Builder("test-sender").addData("kind", "invite").build()))
                withTimeout(5000) { started.await() }
                assertTrue(CallPushIntake.handle(context, RemoteMessage.Builder("test-sender").addData("kind", "end").build()))
                gate.complete(Unit)
                withTimeout(5000) { while (signals.none { it.stage == NativeMediaStage.PUSH_REFUSED }) delay(10) }
                assertEquals("callEndedBeforeAdmission", signals.single { it.stage == NativeMediaStage.PUSH_REFUSED }.reason)
                assertTrue(signals.none { it.stage == NativeMediaStage.CORE_ADD_REQUESTED || it.stage == NativeMediaStage.REPORTED })
                assertTrue(TelecomEvidence.calls().isEmpty())
            } finally {
                gate.completeExceptionally(CancellationException("test cleanup"))
                CallPushIntake.configure()
                NativeMediaSignals.observers -= observer
            }
        }
    }

    @Test fun customDecoderReportsThroughCoreTelecomAndRefusalKeepsItsReason() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia
            val signals = mutableListOf<NativeMediaSignal>()
            val observer = NativeMediaObserver { signals += it }
            NativeMediaSignals.observers += observer
            val id = "custom-push-${System.nanoTime()}"
            fun state() = (engine.snapshot["conversations"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == id }
            fun message(kind: String) = RemoteMessage.Builder("test-sender").addData("kind", kind).build()
            val push = IncomingCallPush(id, "$id-event", mapOf("handleType" to "generic", "value" to "caller", "displayName" to "Custom caller"))
            val decoder = CallPushDecoder { data ->
                when (data["kind"]) {
                    "invite" -> DecodedCallPush.Incoming(push)
                    "end" -> DecodedCallPush.Ended(EndedCallPush(id, "$id-end", "remoteEnded"))
                    else -> null
                }
            }
            try {
                engine.nativeMedia = AndroidNativeMedia.NONE
                CallPushIntake.configure(decoder, CallPushAdmission { CallPushDecision.Refuse("accountMismatch") })
                assertTrue(CallPushIntake.handle(context, message("invite")))
                withTimeout(5000) { while (signals.none { it.serverCallId == id && it.stage == NativeMediaStage.PUSH_REFUSED }) delay(10) }
                assertEquals("accountMismatch", signals.single { it.stage == NativeMediaStage.PUSH_REFUSED }.reason)
                assertTrue(signals.none { it.stage == NativeMediaStage.CORE_ADD_REQUESTED || it.stage == NativeMediaStage.REPORTED })
                assertNull(state())
                assertTrue(TelecomEvidence.calls().isEmpty())
                assertFalse(CallPushIntake.handle(context, message("alert")))

                CallPushIntake.configure(decoder)
                assertTrue(CallPushIntake.handle(context, message("invite")))
                withTimeout(5000) { while (state()?.get("status") != "ringing") delay(10) }
                withTimeout(5000) { while (signals.none { it.serverCallId == id && it.stage == NativeMediaStage.CORE_ADMITTED }) delay(10) }
                assertEquals(1, TelecomEvidence.calls().size)
                assertTrue(CallPushIntake.handle(context, message("invite")))
                delay(100)
                assertEquals(1, signals.count { it.stage == NativeMediaStage.CORE_ADMITTED })
                assertEquals(1, TelecomEvidence.calls().size)
                assertTrue(CallPushIntake.handle(context, message("end")))
                withTimeout(5000) { while (state() != null || TelecomEvidence.calls().isNotEmpty()) delay(10) }
            } finally {
                CallPushIntake.configure()
                if (state() != null) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(5000) { while (TelecomEvidence.calls().isNotEmpty()) delay(10) }
                engine.nativeMedia = previousMedia
                NativeMediaSignals.observers -= observer
            }
        }
    }
}
