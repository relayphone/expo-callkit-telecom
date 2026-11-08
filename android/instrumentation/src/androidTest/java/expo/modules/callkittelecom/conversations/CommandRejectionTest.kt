package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandRejectionTest {
    private fun details(error: Exception): JSONObject {
        val rejection = CommandRejection.from(error)
        assertTrue(rejection.encodedMessage.startsWith("expo-conversation:"))
        val result = JSONObject(rejection.encodedMessage.removePrefix("expo-conversation:"))
        assertEquals(rejection.code, result.getString("code"))
        return result
    }

    @Test fun argumentLookupAndUnsupportedFailuresHaveTypedDetails() {
        for (code in listOf("invalid_arguments", "unknown_conversation", "unsupported")) {
            val result = details(IllegalStateException(code))
            assertEquals(code, result.getString("code"))
            assertEquals("Conversation operation failed", result.getString("message"))
            assertEquals(2, result.length())
        }
    }

    @Test fun timeoutHasTypedDetailsButOrdinaryCancellationPropagates() = runBlocking {
        val timeout = runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull()
        assertTrue(timeout is TimeoutCancellationException)
        val result = details(timeout as TimeoutCancellationException)
        assertEquals("deadline_exceeded", result.getString("code"))
        assertEquals("Conversation deadline exceeded", result.getString("message"))
        val cancellation = CancellationException("credential-secret")
        assertSame(cancellation, runCatching { CommandRejection.from(cancellation) }.exceptionOrNull())
    }

    @Test fun conversationAndHandlerFailuresPreserveOnlySanitizedDetails() {
        val native = details(ConversationFailure("conversation", 4))
        assertEquals("conversation", native.getString("code"))
        assertEquals("androidx.core.telecom", native.getString("nativeDomain"))
        assertEquals(4, native.getInt("nativeCode"))
        assertEquals(4, native.length())
        for (code in listOf("handler_failed", "deadline_exceeded")) {
            val result = details(ConversationFailure(code))
            assertEquals(code, result.getString("code"))
            assertEquals(2, result.length())
        }
        val unknown = details(IllegalArgumentException("credential-secret"))
        assertEquals("conversation", unknown.getString("code"))
        assertEquals("Conversation operation failed", unknown.getString("message"))
        assertFalse(unknown.toString().contains("credential-secret"))
    }
}
