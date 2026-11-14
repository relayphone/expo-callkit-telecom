package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationCommandOutcomeTest {
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
