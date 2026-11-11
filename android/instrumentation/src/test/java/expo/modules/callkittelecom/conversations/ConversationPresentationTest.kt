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

    @Test fun heldSummaryUsesDefaultWordingUnlessOverridden() {
        val withHeld = defaults.copy(heldSummary = "1 call on hold")
        assertEquals(withHeld, ConversationPresentation.resolve(emptyMap(), CallPhase.ONGOING, withHeld))
        ConversationPresentation.configure { _, _ -> CallNotificationContent(heldSummary = "Another call is paused") }
        assertEquals("Another call is paused", ConversationPresentation.resolve(emptyMap(), CallPhase.ONGOING, withHeld).heldSummary)
    }

    @Test fun primaryPrefersActiveThenMostRecentHeldAndWaitingHasSeparateEntries() {
        val roster = CallNotificationRoster()
        fun put(id: String, status: String) = roster.put(id, mapOf("id" to id, "status" to status))
        roster.put("first", mapOf("status" to "ringing"))
        assertEquals("first", roster.plan().primary?.get("id")); assertNull(roster.plan().heldSummary)
        put("first", "connected"); put("waiting", "ringing")
        assertEquals("first", roster.plan().primary?.get("id"))
        assertEquals(listOf("waiting"), roster.plan().waiting.map { it["id"] })
        put("first", "held"); put("waiting", "connecting")
        assertEquals("waiting", roster.plan().primary?.get("id")); assertTrue(roster.plan().waiting.isEmpty())
        assertEquals("1 call on hold", roster.plan().heldSummary)
        put("waiting", "held")
        assertEquals("waiting", roster.plan().primary?.get("id"))
        assertEquals("2 calls on hold", roster.plan().heldSummary)
        roster.put("first", mapOf("id" to "first", "status" to "held", "isMuted" to true))
        assertEquals("waiting", roster.plan().primary?.get("id"))
        roster.remove("waiting")
        assertEquals("first", roster.plan().primary?.get("id"))
        roster.refresh(listOf(mapOf("id" to "first", "status" to "ended"), mapOf("id" to "unpresented", "status" to "ringing")))
        assertNull(roster.plan().primary); assertTrue(roster.plan().waiting.isEmpty())
    }

    @Test fun pendingRemovalRemainsPresentedAlongsideAnActiveCall() {
        val roster = CallNotificationRoster()
        roster.put("ending", mapOf("status" to "connected", "members" to listOf("private caller")))
        assertTrue(roster.ending("ending"))
        assertEquals(mapOf("id" to "ending", "status" to "ending"), roster.plan().primary)
        roster.put("active", mapOf("status" to "connected"))
        assertEquals("active", roster.plan().primary?.get("id"))
        assertEquals(listOf(mapOf("id" to "ending", "status" to "ending")), roster.plan().waiting)
        assertEquals("ending", roster.plan("ending").primary?.get("id"))
        assertEquals(listOf("active"), roster.plan("ending").waiting.map { it["id"] })
        roster.remove("active")
        assertEquals("ending", roster.plan().primary?.get("id"))
        roster.remove("ending")
        assertNull(roster.plan().primary)
    }
}
