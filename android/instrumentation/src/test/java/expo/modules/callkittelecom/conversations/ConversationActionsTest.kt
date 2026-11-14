package expo.modules.callkittelecom.conversations

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConversationActionsTest {
    private fun fixture(body: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { body(scope) } finally { ConversationActions.configure(null); scope.cancel() }
    }

    @Test fun routesEveryNativeActionWithItsIdentityAndOriginButNeverStart() = fixture { scope ->
        val seen = mutableListOf<ConversationAction>()
        ConversationActions.configure { action -> synchronized(seen) { seen += action }; ConversationActionResult.Proceed() }
        val runner = ConversationActionDecision(scope, { _, _ -> })
        for (origin in NativeActionOrigin.entries) {
            val actions = listOf(NativePerformedAction.Answer, NativePerformedAction.End,
                NativePerformedAction.Hold(true), NativePerformedAction.Mute(false))
            for (action in actions) NativeActionReceipt("call", origin, action, "server").decide(runner)
            NativeActionReceipt("call", origin, NativePerformedAction.End, "server", decline = true).decide(runner)
            assertNull(NativeActionReceipt("call", origin, NativePerformedAction.Start).decide(runner))
            assertNull(NativeActionReceipt("call", origin, NativePerformedAction.Tones("1")).decide(runner))
        }
        assertEquals(listOf("app", "system").flatMap { origin -> listOf(
            ConversationAction.Answer("call", "server", origin), ConversationAction.End("call", "server", origin),
            ConversationAction.Hold("call", "server", origin, true), ConversationAction.Mute("call", "server", origin, false),
            ConversationAction.Decline("call", "server", origin)) }, seen)
    }

    @Test fun receiptMemoizesTheDecisionBeforeRequestAndItsReentrantSystemEcho() = fixture { scope ->
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val result = ConversationActionResult.Proceed(mapOf("provider" to "livekit"))
        ConversationActions.configure { count.incrementAndGet(); entered.complete(Unit); gate.await(); result }
        val receipt = NativeActionReceipt("call", NativeActionOrigin.APP, NativePerformedAction.Answer)
        val runner = ConversationActionDecision(scope, { _, _ -> })
        coroutineScope {
            val app = async(start = CoroutineStart.UNDISPATCHED) { receipt.decide(runner) }
            entered.await()
            val echo = async(start = CoroutineStart.UNDISPATCHED) { receipt.decide(runner) }
            assertFalse(echo.isCompleted)
            gate.complete(Unit)
            assertSame(result, app.await()); assertSame(result, echo.await())
        }
        assertEquals(1, count.get())
    }

    @Test fun nullHandlerPreservesTheNativeEffectAndProducesNoHandlerSignals() = fixture { scope ->
        val receipt = NativeActionReceipt("call", NativeActionOrigin.APP, NativePerformedAction.Answer)
        assertNull(receipt.decide(ConversationActionDecision(scope, { _, _ -> fail("No handler signal") })))
        var effects = 0
        receipt.perform({ effects++ }, {}) { _, _ -> }
        assertEquals(1, effects)
    }

    @Test fun refusalMapsToTypedFailuresAndOnlyAnswerOrAppControlsCanVeto() {
        assertEquals("refused", NativeActionDecision(ConversationActionResult.Refuse("claimLost")).rejection().code)
        assertEquals("failed", ConversationActionResult.Refuse("claimLost", "invalid").validEndReason())
        assertEquals("answeredElsewhere", ConversationActionResult.Refuse("claimLost", "answeredElsewhere").validEndReason())
        assertFalse(ConversationAction.Hold("call", null, "app", true).canRefuse("system"))
        for (origin in listOf("app", "system")) {
            assertTrue(ConversationAction.Answer("call", null, origin).canRefuse())
            assertFalse(ConversationAction.End("call", null, origin).canRefuse())
            assertFalse(ConversationAction.Decline("call", null, origin).canRefuse())
            assertEquals(origin == "app", ConversationAction.Hold("call", null, origin, true).canRefuse())
            assertEquals(origin == "app", ConversationAction.Mute("call", null, origin, true).canRefuse())
        }
    }

    @Test fun thrownAndOverdueHandlersReturnSanitizedRefusalsAndOrderedSignals() = fixture { scope ->
        assertEquals(3500L, CONVERSATION_ACTION_BUDGET_MILLIS)
        assertEquals(3000L, CONVERSATION_END_BUDGET_MILLIS)
        for ((handler, reason) in listOf(
            ConversationActionHandler { error("Private server response") } to "handlerFailed",
            ConversationActionHandler { awaitCancellation() } to "handlerTimedOut",
        )) {
            val signals = mutableListOf<Pair<NativeMediaStage, String?>>()
            val result = ConversationActionDecision(scope, { stage, outcome -> signals += stage to outcome }, budgetMillis = 40)
                .ask(ConversationAction.Answer("call", null, "system"), handler)
            assertEquals(ConversationActionResult.Refuse(reason), result.result)
            assertEquals(if (reason == "handlerTimedOut") "deadline_exceeded" else "handler_failed", result.rejection().code)
            assertEquals(listOf(NativeMediaStage.HANDLER_STARTED to null, NativeMediaStage.HANDLER_RESPONDED to reason), signals)
        }
    }

    @Test fun explicitRefusalsKeepTheirReasonEvenWhenItResemblesAnInternalFailure() = fixture { scope ->
        for (reason in listOf("claimLost", "handlerTimedOut", "handlerFailed")) {
            val receipt = NativeActionReceipt("call", NativeActionOrigin.APP, NativePerformedAction.Hold(true))
            ConversationActions.configure { ConversationActionResult.Refuse(reason) }
            receipt.decide(ConversationActionDecision(scope, { _, _ -> }))
            val wire = org.json.JSONObject(CommandRejection.from(receipt.rejection()).encodedMessage.removePrefix("expo-conversation:"))
            assertEquals("refused", wire.getString("code"))
            assertEquals(reason, wire.getString("message"))
        }
    }

    @Test fun aRemoteCompletionSettlesEndAndDeclineWithoutWaitingForTheHandlerOrRepeatingTelemetry() = fixture { scope ->
        for (decline in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val signals = mutableListOf<Pair<NativeMediaStage, String?>>()
            ConversationActions.configure { action ->
                assertEquals(decline, action is ConversationAction.Decline)
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                finished.complete(Unit)
                ConversationActionResult.Refuse("lateRefusal")
            }
            val receipt = NativeActionReceipt("call", NativeActionOrigin.APP, NativePerformedAction.End, decline = decline)
            try {
                coroutineScope {
                    val result = async(start = CoroutineStart.UNDISPATCHED) {
                        receipt.decide(ConversationActionDecision(scope, { stage, reason -> signals += stage to reason }))
                    }
                    entered.await()
                    receipt.completeFromRemote()
                    assertTrue(withTimeout(250) { result.await() } is ConversationActionResult.Proceed)
                    var effects = 0
                    var performed = 0
                    repeat(2) { receipt.perform({ effects++ }, {}) { _, _ -> performed++ } }
                    assertEquals(1, effects); assertEquals(1, performed)
                    release.complete(Unit); withTimeout(1000) { finished.await() }; yield()
                    assertEquals(listOf(NativeMediaStage.HANDLER_STARTED to null,
                        NativeMediaStage.HANDLER_RESPONDED to "proceed"), signals)
                }
            } finally { release.complete(Unit) }
        }
    }

    @Test fun endAlwaysPerformsAfterItsShorterBudgetEvenWhenTheHandlerIgnoresCancellation() = fixture { scope ->
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val signals = mutableListOf<String?>()
        ConversationActions.configure {
            withContext(NonCancellable) { entered.complete(Unit); gate.await() }
            finished.complete(Unit)
            ConversationActionResult.Refuse("lateRefusal")
        }
        val receipt = NativeActionReceipt("call", NativeActionOrigin.SYSTEM, NativePerformedAction.End)
        try {
            val runner = ConversationActionDecision(scope, { _, reason -> signals += reason }, budgetMillis = 1000, endBudgetMillis = 40)
            val decision = withTimeout(500) { receipt.decide(runner) }
            assertTrue(entered.isCompleted)
            assertEquals(ConversationActionResult.Refuse("handlerTimedOut"), decision)
            assertFalse(receipt.conversationAction!!.canRefuse())
            var disconnects = 0
            receipt.perform({ disconnects++ }, {}) { _, _ -> }
            gate.complete(Unit); withTimeout(1000) { finished.await() }
            receipt.perform({ disconnects++ }, {}) { _, _ -> }
            assertEquals(1, disconnects)
            assertEquals(listOf(null, "handlerTimedOut"), signals)
        } finally { gate.complete(Unit) }
    }
}
