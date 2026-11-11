package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CallPushIntakeTest {
    private val caller = mapOf<String, Any?>("handleType" to "generic", "value" to "caller", "displayName" to "Caller")
    private val invitation = IncomingCallPush("server", "event", caller, true,
        mapOf("provider" to "test"), mapOf("nested" to listOf(null, mapOf("count" to 3))))

    @After fun reset() { CallPushIntake.configure() }

    @Test fun defaultDecoderReadsIncomingAliasesAndNestedJson() {
        for (type in listOf("incomingCall", "incoming_call")) {
            val decoded = DefaultCallPushDecoder.decode(mapOf("messageType" to type,
                type to JSONObject(invitation.payload()).toString()))
            assertEquals(DecodedCallPush.Incoming(invitation), decoded)
        }
    }

    @Test fun expirySurvivesTypedAndDefaultDecodedPushes() {
        val expiring = invitation.copy(expiresAtMillis = 1_900_000_000_123L)
        assertEquals(expiring.expiresAtMillis, expiring.payload()["expiresAtMillis"])
        for (type in listOf("incomingCall", "incoming_call")) {
            assertEquals(DecodedCallPush.Incoming(expiring), DefaultCallPushDecoder.decode(mapOf(
                "messageType" to type, type to JSONObject(expiring.payload()).toString())))
        }
        assertNull((DefaultCallPushDecoder.decode(mapOf("messageType" to "incomingCall",
            "incomingCall" to JSONObject(invitation.payload()).toString())) as DecodedCallPush.Incoming).push.expiresAtMillis)
    }

    @Test fun defaultDecoderReadsEndedAliasesAndIgnoresOtherData() {
        val end = EndedCallPush("server", "end-event", "answeredElsewhere")
        for (type in listOf("callEnded", "call_ended")) {
            assertEquals(DecodedCallPush.Ended(end), DefaultCallPushDecoder.decode(mapOf(
                "messageType" to type, type to JSONObject(end.payload()).toString())))
        }
        for (data in listOf(emptyMap(), mapOf("messageType" to "alert"), mapOf("incomingCall" to "{}"))) {
            assertNull(DefaultCallPushDecoder.decode(data))
        }
    }

    @Test fun legacyEndsKeepTheDefaultReason() {
        for (reason in listOf(null, JSONObject.NULL, 7)) {
            val payload = mutableMapOf<String, Any?>("serverCallId" to "server", "eventId" to "end")
            if (reason != null) payload["reason"] = reason
            assertEquals(DecodedCallPush.Ended(EndedCallPush("server", "end", "remoteEnded")),
                DefaultCallPushDecoder.decode(mapOf("messageType" to "callEnded", "callEnded" to JSONObject(payload).toString())))
        }
    }

    @Test fun customDecoderReachesEngineBeforeJavaScriptAndKeepsPriority() = runBlocking {
        val order = mutableListOf<String>()
        val signals = mutableListOf<NativeMediaSignal>()
        val target = NativeCallPush(
            incoming = { payload ->
                assertEquals(invitation.payload(), payload)
                order += "report"
                mapOf("status" to "reported", "id" to "server")
            },
            remoteEnd = { _, _ -> fail("unexpected end") }, canWarm = { true }, shouldWarm = { true },
            warmJavaScript = { order += "warm" },
            signal = { id, stage, measurements, reason -> signals += NativeMediaSignal(id, stage, 0, measurements, reason) },
        )
        CallPushIntake.configure(CallPushDecoder { data ->
            assertEquals(mapOf("custom" to "invitation"), data)
            DecodedCallPush.Incoming(invitation)
        })
        var decoded: DecodedCallPush? = null
        var admission: CallPushAdmission? = null
        assertTrue(CallPushIntake.receive(mapOf("custom" to "invitation")) { push, check -> decoded = push; admission = check })
        assertEquals("reported", target.handle(decoded!!, true, admission!!)?.get("status"))
        assertEquals(listOf("report", "warm"), order)
        assertEquals(listOf(NativeMediaStage.PUSH_RECEIVED, NativeMediaStage.JS_WARM_STARTED), signals.map { it.stage })
        assertEquals(1L, signals.first().measurements["highPriority"])
    }

    @Test fun admissionRefusalReportsNothingAndEmitsItsReason() = runBlocking {
        val signals = mutableListOf<NativeMediaSignal>()
        val target = NativeCallPush(
            incoming = { fail("refused push reached Telecom"); emptyMap() },
            remoteEnd = { _, _ -> fail("unexpected end") }, canWarm = { fail("unexpected warm check"); false },
            shouldWarm = { false }, warmJavaScript = { fail("refused push warmed JS") },
            signal = { id, stage, measurements, reason -> signals += NativeMediaSignal(id, stage, 0, measurements, reason) },
        )
        CallPushIntake.configure(CallPushDecoder { DecodedCallPush.Incoming(invitation) }, CallPushAdmission {
            assertEquals(invitation, it)
            CallPushDecision.Refuse("expired")
        })
        var decoded: DecodedCallPush? = null
        var admission: CallPushAdmission? = null
        assertTrue(CallPushIntake.receive(emptyMap()) { push, check -> decoded = push; admission = check })
        assertNull(target.handle(decoded!!, false, admission!!))
        assertEquals(listOf(NativeMediaStage.PUSH_RECEIVED, NativeMediaStage.PUSH_REFUSED), signals.map { it.stage })
        assertEquals("expired", signals.last().reason)
        assertEquals("server", signals.last().serverCallId)
        assertEquals(0L, signals.first().measurements["highPriority"])
    }

    @Test fun nonCallAndBrokenDecoderReturnFalseWithoutDispatch() {
        assertFalse(CallPushIntake.receive(mapOf("messageType" to "alert")) { _, _ -> fail("unexpected dispatch") })
        CallPushIntake.configure(CallPushDecoder { error("invalid payload") })
        assertFalse(CallPushIntake.receive(emptyMap()) { _, _ -> fail("unexpected dispatch") })
    }

    @Test fun endBypassesIncomingAdmissionAndUsesRemoteEnd() = runBlocking {
        val end = EndedCallPush("server", "ended-event", "remoteEnded")
        var received: Map<String, Any?>? = null
        val target = NativeCallPush(
            incoming = { fail("end reported incoming"); emptyMap() }, remoteEnd = { payload, consumed -> received = payload; consumed() },
            canWarm = { false }, shouldWarm = { false }, warmJavaScript = { fail("end warmed JS") },
            signal = { _, _, _, _ -> fail("end emitted incoming signal") },
        )
        assertNull(target.handle(DecodedCallPush.Ended(end), true, CallPushAdmission { fail("end admitted as incoming"); CallPushDecision.Admit }))
        assertEquals(end.payload(), received)
    }

    @Test fun unsuccessfulReportDoesNotWarmJavaScript() = runBlocking {
        val target = NativeCallPush(
            incoming = { mapOf("status" to "atCapacity") }, remoteEnd = { _, _ -> },
            canWarm = { fail("capacity checked warm state"); false }, shouldWarm = { true },
            warmJavaScript = { fail("capacity warmed JS") }, signal = { _, _, _, _ -> },
        )
        assertEquals("atCapacity", target.handle(DecodedCallPush.Incoming(invitation), true)?.get("status"))
    }

    @Test fun endDuringConcurrentAdmissionsPreventsEveryPendingReport() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val signals = mutableListOf<NativeMediaSignal>()
        var ends = 0
        val target = NativeCallPush(
            incoming = { fail("ended push reached Telecom"); emptyMap() }, remoteEnd = { _, consumed -> ends++; consumed() },
            canWarm = { false }, shouldWarm = { true }, warmJavaScript = { fail("ended push warmed JS") },
            signal = { id, stage, measurements, reason -> signals += NativeMediaSignal(id, stage, 0, measurements, reason) },
        )
        val admission = CallPushAdmission { gate.await(); CallPushDecision.Admit }
        val pending = (1..2).map { async(start = CoroutineStart.UNDISPATCHED) {
            target.handle(DecodedCallPush.Incoming(invitation), true, admission)
        } }
        target.handle(DecodedCallPush.Ended(EndedCallPush("server", "end", "remoteEnded")), true)
        gate.complete(Unit)
        assertEquals(listOf(null, null), pending.awaitAll())
        assertEquals(1, ends)
        assertEquals(listOf("callEndedBeforeAdmission", "callEndedBeforeAdmission"),
            signals.filter { it.stage == NativeMediaStage.PUSH_REFUSED }.map { it.reason })
    }

    @Test fun cancelledAdmissionDoesNotRetainAnEndForALaterInvitation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var reports = 0
        val target = NativeCallPush(
            incoming = { reports++; mapOf("status" to "reported", "id" to "server") }, remoteEnd = { _, _ -> },
            canWarm = { false }, shouldWarm = { false }, warmJavaScript = {}, signal = { _, _, _, _ -> },
        )
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            target.handle(DecodedCallPush.Incoming(invitation), true, CallPushAdmission { gate.await(); CallPushDecision.Admit })
        }
        pending.cancelAndJoin()
        target.handle(DecodedCallPush.Ended(EndedCallPush("server", "end", "remoteEnded")), true)
        assertEquals("reported", target.handle(DecodedCallPush.Incoming(invitation), true)?.get("status"))
        assertEquals(1, reports)
    }

    @Test fun previouslyConsumedUnknownEndCannotRefuseALaterPendingInvitation() = runBlocking {
        for (consumedDirectly in listOf(true, false)) {
            val ends = NativeEndedIntake { 0L }
            val end = EndedCallPush("server", "already-consumed", "remoteEnded")
            val gate = CompletableDeferred<Unit>()
            var reports = 0
            val signals = mutableListOf<NativeMediaStage>()
            val target = NativeCallPush(
                incoming = { reports++; mapOf("status" to "reported", "id" to "server") },
                remoteEnd = { payload, consumed -> NativeCallEnded.wire(payload)?.let { ends.accept(it, consumed) }; Unit },
                canWarm = { false }, shouldWarm = { false }, warmJavaScript = {},
                signal = { _, stage, _, _ -> signals += stage },
            )
            if (consumedDirectly) assertNull(ends.accept(NativeCallEnded.wire(end.payload())!!))
            else target.handle(DecodedCallPush.Ended(end), true)
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                target.handle(DecodedCallPush.Incoming(invitation), true,
                    CallPushAdmission { gate.await(); CallPushDecision.Admit })
            }
            target.handle(DecodedCallPush.Ended(end), true)
            gate.complete(Unit)
            assertEquals("reported", pending.await()?.get("status"))
            assertEquals(1, reports)
            assertFalse(signals.contains(NativeMediaStage.PUSH_REFUSED))
        }
    }
}
