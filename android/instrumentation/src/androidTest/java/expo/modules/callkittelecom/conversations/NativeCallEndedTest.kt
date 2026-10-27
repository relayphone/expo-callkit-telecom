package expo.modules.callkittelecom.conversations

import android.telecom.DisconnectCause
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCallEndedTest {
    private fun payload(event: String = "end", server: String = "server", reason: String = "answeredElsewhere") =
        mapOf<String, Any?>("eventId" to event, "serverCallId" to server, "reason" to reason)

    @Test fun localTypedInputDoesNotSpendTheCorrectedEvent() {
        val intake = NativeEndedIntake { 0 }
        val entry = intake.reserve("server", "native")
        assertTrue(runCatching { NativeCallEnded.typed(payload(reason = "local")) }.isFailure)
        assertSame(entry, intake.accept(NativeCallEnded.typed(payload())!!))
        assertNull(intake.accept(NativeCallEnded.typed(payload())!!))
    }

    @Test fun unknownNotificationConsumesOnlyItsEventIdentity() {
        val intake = NativeEndedIntake { 0 }
        val end = NativeCallEnded.typed(payload())!!
        assertNull(intake.accept(end))
        val incoming = intake.reserve("server", "native")
        assertNull(incoming.intent)
        assertNull(intake.accept(end))
        assertSame(incoming, intake.accept(end.copy(eventId = "new-event")))
    }

    @Test fun endDedupExpiresAfterExactlyTwoMinutesWhileActiveOwnershipSurvives() {
        var clock = 0L
        val intake = NativeEndedIntake { clock }
        val entry = intake.reserve("server", "native")
        val end = NativeCallEnded.typed(payload())!!
        assertSame(entry, intake.accept(end))
        clock = 120_000
        assertNull(intake.accept(end))
        clock++
        assertSame(entry, intake.accept(end))
    }

    @Test fun knownPendingAdmissionRetainsTheEndUntilItsActualReport() {
        var clock = 0L
        val intake = NativeEndedIntake { clock }
        val entry = intake.reserve("server", "native")
        val end = NativeCallEnded.typed(payload())!!
        assertSame(entry, intake.accept(end))
        clock = 300_000
        assertTrue(intake.current(entry))
        assertSame(entry, intake.accept(end.copy(eventId = "late")))
        intake.admitted(entry); intake.settled(entry)
        assertFalse(entry.pending)
        assertEquals("answeredElsewhere", entry.intent!!.payload.reason)
    }

    @Test fun removedIncomingOwnershipRetainsMetadataButCannotAliasANewGeneration() {
        var clock = 0L
        val intake = NativeEndedIntake { clock }
        val old = intake.reserve("server", "native")
        intake.admitted(old); intake.settled(old); intake.removed(old)
        assertSame(old, intake.accept(NativeCallEnded.typed(payload())!!))
        clock = 120_001
        val replacement = intake.reserve("server", "native")
        assertNotSame(old, replacement)
        assertFalse(intake.current(old)); assertTrue(intake.current(replacement))
        assertNull(replacement.intent)
    }

    @Test fun identifiersValidateFoundationWhitespaceWithoutChangingTheirBytes() {
        val intake = NativeEndedIntake { 0 }
        val entry = intake.reserve(" server ", "native")
        assertNull(NativeCallEnded.typed(payload(event = "\u200B\u0085\u00A0")))
        assertNull(intake.accept(NativeCallEnded.typed(payload())!!))
        val exact = NativeCallEnded.typed(payload(event = " end ", server = " server "))!!
        assertEquals(" end ", exact.eventId)
        assertEquals(" server ", exact.serverCallId)
        assertSame(entry, intake.accept(exact))
        // Foundation's trim set does not reject otherwise nonempty control identifiers.
        assertTrue(NativeCallEnded.validIdentifier("\u001C"))
    }

    @Test fun failedUnreportedAdmissionReleasesOwnershipButKeepsEndEventDedup() {
        val intake = NativeEndedIntake { 0 }
        val failed = intake.reserve("server", "native")
        val end = NativeCallEnded.typed(payload())!!
        assertSame(failed, intake.accept(end))
        intake.removed(failed)
        assertTrue("A pending platform report must keep its terminal intent", intake.current(failed))
        intake.settled(failed)
        assertFalse(intake.current(failed))
        val next = intake.reserve("server", "native")
        assertNull(next.intent)
        assertNull(intake.accept(end))
        assertSame(next, intake.accept(end.copy(eventId = "corrected")))
    }

    @Test fun wireReasonsNormalizeWithoutWeakeningTypedReasons() {
        for (reason in listOf(null, "local", "future-reason")) {
            assertEquals("remoteEnded", NativeCallEnded.wire(payload() + ("reason" to reason))!!.reason)
            assertTrue(runCatching { NativeCallEnded.typed(payload() + ("reason" to reason)) }.isFailure)
        }
        assertEquals("remoteEnded", NativeCallEnded.wire(payload() - "reason")!!.reason)
        assertNull(NativeCallEnded.wire(emptyMap()))
    }

    @Test fun supportedNativeCodesPreserveTheCanonicalOwnershipReason() {
        val end = NativeCallEnded.typed(payload())!!
        for (sdk in listOf(34, 35)) {
            assertEquals(DisconnectCause.REMOTE, end.disconnectCause(sdk).code)
            assertEquals("answeredElsewhere", end.disconnectCause(sdk).reason)
        }
        assertEquals(DisconnectCause.ANSWERED_ELSEWHERE, end.disconnectCause(33).code)
        assertEquals("answeredElsewhere", end.disconnectCause(33).reason)
    }
}
