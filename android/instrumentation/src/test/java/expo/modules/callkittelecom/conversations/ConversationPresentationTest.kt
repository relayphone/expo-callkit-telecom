package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ConversationPresentationTest {
    @Test fun reconnectingKeepsConnectedNotificationPriorityAndPresentation() {
        val roster = CallNotificationRoster()
        roster.put("first", mapOf("status" to "connected"))
        roster.put("second", mapOf("status" to "connected"))
        roster.put("first", mapOf("status" to "reconnecting"))
        assertEquals("second", roster.plan().primary?.get("id"))
        assertEquals(2, roster.liveStates().size)
        roster.remove("second")
        assertEquals("reconnecting", roster.plan().primary?.get("status"))
    }

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

    @Test fun endingTitleUsesItsOwnOverrideAndFallsBackForNullOrThrowingCustomizers() {
        val ending = CallNotificationContent(endingTitle = "Ending call…")
        val entry = mapOf("id" to "call", "status" to "ending")
        assertEquals("Ending call…", ConversationPresentation.resolve(entry, CallPhase.ENDING, ending).endingTitle)
        ConversationPresentation.configure { received, phase ->
            assertSame(entry, received); assertEquals(CallPhase.ENDING, phase)
            CallNotificationContent(endingTitle = "Zakończenie połączenia…")
        }
        assertEquals("Zakończenie połączenia…", ConversationPresentation.resolve(entry, CallPhase.ENDING, ending).endingTitle)
        ConversationPresentation.configure { _, _ -> CallNotificationContent(title = "Caller title") }
        assertEquals("Ending call…", ConversationPresentation.resolve(entry, CallPhase.ENDING, ending).endingTitle)
        ConversationPresentation.configure { _, _ -> error("Unavailable localization") }
        assertEquals("Ending call…", ConversationPresentation.resolve(entry, CallPhase.ENDING, ending).endingTitle)
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

    @Test fun refreshIncludesEveryLiveStateWithoutChangingThePresentationPlan() {
        val roster = CallNotificationRoster()
        for (status in listOf("ringing", "dialing", "connecting", "connected", "held", "ending", "ended")) {
            roster.put(status, mapOf("status" to status))
        }
        val before = roster.plan("ending")
        assertEquals(listOf("ringing", "dialing", "connecting", "connected", "held"),
            roster.liveStates().map { it["id"] })
        assertEquals(before, roster.plan("ending"))
        assertEquals("ending", before.primary?.get("id"))
        assertEquals(listOf("ringing", "dialing", "connecting", "connected", "held"), before.waiting.map { it["id"] })
        roster.remove("ringing")
        assertFalse(roster.liveStates().any { it["id"] == "ringing" })
    }

    @Test fun ringingAlertsIncludeTheInitialPostAndShareThePackageCooldown() {
        val schedule = RingingAlertSchedule()
        assertEquals(0L, schedule.delayMillis(100_000_000L))
        schedule.requested(1); schedule.posted(1, 100_000_000L)
        assertEquals(900L, schedule.delayMillis(300_000_000L))
        assertEquals(700L, schedule.delayMillis(500_000_000L))
        assertEquals(1L, schedule.delayMillis(1199_000_000L))
        assertEquals(0L, schedule.delayMillis(1200_000_000L))
        // Another card's alert starts the same package-wide window.
        schedule.requested(2); schedule.posted(2, 1200_000_000L)
        assertEquals(1100L, schedule.delayMillis(1200_000_000L))
        assertEquals(0L, schedule.delayMillis(2300_000_000L))
    }

    @Test fun aForegroundRequestDoesNotOpenTheGateBeforeItsPlatformPostIsObserved() {
        val schedule = RingingAlertSchedule()
        schedule.requested(1); schedule.posted(1, 100_000_000L)
        schedule.requested(2)
        assertEquals(1100L, schedule.delayMillis(5000_000_000L))
        schedule.posted(2, 5000_000_000L)
        assertEquals(1100L, schedule.delayMillis(5000_000_000L))
        assertEquals(1L, schedule.delayMillis(6099_000_000L))
        assertEquals(0L, schedule.delayMillis(6100_000_000L))
    }

    @Test fun aNewForegroundPostCannotBeUnlockedByAnOlderObservation() {
        val schedule = RingingAlertSchedule()
        schedule.requested(1)
        schedule.requested(2)
        schedule.posted(1, 100_000_000L)
        assertEquals(1100L, schedule.delayMillis(5000_000_000L))
        schedule.posted(2, 5000_000_000L)
        assertEquals(1100L, schedule.delayMillis(5000_000_000L))
    }

    @Test fun fractionalMillisecondsRoundUpUntilTheEntireIntervalHasElapsed() {
        val schedule = RingingAlertSchedule()
        schedule.requested(1); schedule.posted(1, 100_500_000L)
        assertEquals(1L, schedule.delayMillis(1200_499_999L))
        assertEquals(0L, schedule.delayMillis(1200_500_000L))
    }
}
