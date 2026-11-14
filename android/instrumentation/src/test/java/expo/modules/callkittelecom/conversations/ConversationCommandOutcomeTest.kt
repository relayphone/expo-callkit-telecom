package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationCommandOutcomeTest {
    @Test fun outcomeDeadlineExceedsThePlatformRemovalDeadline() {
        assertEquals(70_000L, CONVERSATION_COMMAND_OUTCOME_TIMEOUT_MILLIS)
        assertTrue(CONVERSATION_COMMAND_OUTCOME_TIMEOUT_MILLIS > PLATFORM_REMOVAL_DEADLINE_MILLIS)
    }

    @Test fun logicalEndAndExternalControlWaitsTimeOutWithoutCancellingAcceptedCommands() = runBlocking {
        for (settlement in listOf(
            ConversationCommandOutcome.Performed("call", ConversationCommandStatus.ENDED, ConversationEndReason.LOCAL),
            ConversationCommandOutcome.Performed("call", ConversationCommandStatus.HELD),
        )) {
            val externalSettlement = CompletableDeferred<Unit>()
            val accepted: Deferred<ConversationCommandOutcome> = async(start = CoroutineStart.UNDISPATCHED) {
                externalSettlement.await()
                settlement
            }
            try {
                val result = withTimeout(1000) { accepted.awaitCommandOutcome(timeoutMillis = 10) }
                assertEquals(CommandRejection.deadlineExceeded().outcome, result)
                assertEquals(ConversationCommandCode.DEADLINE_EXCEEDED, (result as ConversationCommandOutcome.Failed).code)
                assertTrue("The accepted command must outlive the caller's deadline", accepted.isActive)
                externalSettlement.complete(Unit)
                assertEquals(settlement, accepted.await())
            } finally { externalSettlement.complete(Unit) }
        }
    }

    @Test fun completedSettlementsAreUnchangedByTheOverallWaitBound() = runBlocking {
        for (result in listOf(
            ConversationCommandOutcome.Performed("call", ConversationCommandStatus.CONNECTING),
            ConversationCommandOutcome.Refused("application reason"),
            ConversationCommandOutcome.Failed(ConversationCommandCode.HANDLER_FAILED, "handler failed"),
        )) {
            assertEquals(result, CompletableDeferred(result).awaitCommandOutcome(timeoutMillis = 1000))
        }
    }

    @Test fun aCallersShorterTimeoutPropagatesWithoutCancellingTheAcceptedCommand() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val accepted: Deferred<ConversationCommandOutcome> = async(start = CoroutineStart.UNDISPATCHED) {
            release.await()
            ConversationCommandOutcome.Performed("call", ConversationCommandStatus.CONNECTED)
        }
        try {
            val error = runCatching { withTimeout(10) { accepted.awaitCommandOutcome(timeoutMillis = 1000) } }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertTrue(accepted.isActive)
        } finally { release.complete(Unit) }
    }

    @Test fun nativeFailureAndJavaScriptDetailsHaveOneNormalization() {
        for (code in listOf("deadline_exceeded", "handler_failed", "unknown_conversation",
            "invalid_arguments", "unsupported", "conversation", "future_code")) {
            val rejection = CommandRejection.from(ConversationFailure(code, 4, "private transport message"))
            val native = rejection.outcome as ConversationCommandOutcome.Failed
            val wire = JSONObject(rejection.encodedMessage.removePrefix("expo-conversation:"))
            assertEquals(code, native.code.value)
            assertEquals(wire.getString("code"), native.code.value)
            assertEquals(wire.getString("message"), native.message)
            assertEquals(wire.getInt("nativeCode"), native.nativeCode)
            assertFalse(native.message.contains("private transport message"))
        }
    }

    @Test fun explicitRefusalRetainsExactTextIncludingNamesThatResembleInternalFailures() {
        for (reason in listOf("denied by app", "handlerTimedOut", "handlerFailed", "deadline_exceeded", "")) {
            val rejection = CommandRejection.from(ConversationFailure("refused", message = reason))
            assertEquals(ConversationCommandOutcome.Refused(reason), rejection.outcome)
            val wire = JSONObject(rejection.encodedMessage.removePrefix("expo-conversation:"))
            assertEquals("refused", wire.getString("code"))
            assertEquals(reason, wire.getString("message"))
        }
    }

    @Test fun deadlineMapsToTypedCodeAndOrdinaryCallerCancellationPropagates() = runBlocking {
        val timeout = runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull() as Exception
        val result = CommandRejection.from(timeout).outcome as ConversationCommandOutcome.Failed
        assertEquals(ConversationCommandCode.DEADLINE_EXCEEDED, result.code)
        val cancellation = CancellationException("caller stopped waiting")
        assertSame(cancellation, runCatching { CommandRejection.from(cancellation).outcome }.exceptionOrNull())
    }

    @Test fun performedStateUsesTypedPhaseAndTheOriginalLogicalEndReason() {
        assertEquals(ConversationCommandOutcome.Performed("call", ConversationCommandStatus.CONNECTING),
            performedCommand("call", mapOf("status" to "connecting")))
        assertEquals(ConversationCommandOutcome.Performed("call", ConversationCommandStatus.ENDED, ConversationEndReason.REMOTE_ENDED),
            performedCommand("call", mapOf("status" to "ended", "ended" to mapOf("reason" to "remoteEnded", "at" to "unchanged"))))
    }
}
