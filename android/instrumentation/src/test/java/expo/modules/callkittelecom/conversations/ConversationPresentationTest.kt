package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ConversationPresentationTest {
    @After fun reset() { ConversationPresentation.configure(null) }

    private val defaults = CallNotificationContent(title = "Caller", channelId = "incoming-default")

    @Test fun absentCustomizerAndNullFieldsKeepDefaults() {
        assertEquals(defaults, ConversationPresentation.resolve(emptyMap(), CallPhase.RINGING, defaults))
        ConversationPresentation.configure { _, _ -> CallNotificationContent() }
        val resolved = ConversationPresentation.resolve(emptyMap(), CallPhase.RINGING, defaults)
        assertEquals(defaults, resolved)
        assertNull(resolved.avatar); assertNull(resolved.fullScreenIntent); assertNull(resolved.contentIntent)
        assertTrue(resolved.extraActions.isEmpty())
    }

    @Test fun explicitContentOverridesOnlyItsFieldsAndReceivesTheEntryAndPhase() {
        val entry = mapOf("id" to "call", "status" to "ringing", "members" to emptyList<Any>())
        for (phase in CallPhase.entries) {
            ConversationPresentation.configure { conversation, receivedPhase ->
                assertSame(entry, conversation); assertEquals(phase, receivedPhase)
                CallNotificationContent(title = "App caller")
            }
            assertEquals(defaults.copy(title = "App caller"), ConversationPresentation.resolve(entry, phase, defaults))
            ConversationPresentation.configure { _, _ -> CallNotificationContent(channelId = "app-channel") }
            assertEquals(defaults.copy(channelId = "app-channel"), ConversationPresentation.resolve(entry, phase, defaults))
        }
    }

    @Test fun resetAndThrowingCustomizerRestoreDefaultContent() {
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = "App") }
        ConversationPresentation.configure(null)
        assertEquals(defaults, ConversationPresentation.resolve(emptyMap(), CallPhase.RINGING, defaults))
        ConversationPresentation.configure { _, _ -> error("App content unavailable") }
        assertEquals(defaults, ConversationPresentation.resolve(emptyMap(), CallPhase.RINGING, defaults))
    }
}
