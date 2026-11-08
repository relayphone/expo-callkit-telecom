package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeIdentityReservationsTest {
    @Test fun rawIdentifiersArePreservedAndDuplicateIncomingClaimsShareOwnership() {
        val identities = NativeIdentityReservations()
        for (id in listOf("server/電話 42", " not-a-uuid ", "é", "e\u0301")) {
            val first = identities.reserve(id, NativeCallDirection.INCOMING)
            assertEquals(id, first.id)
            assertSame(first, identities.reserve(id, NativeCallDirection.INCOMING))
            assertTrue(identities.isCurrent(first))
        }
    }

    @Test fun outgoingDuplicatesAndCrossDirectionCollisionsCannotReplaceOwnership() {
        for (direction in NativeCallDirection.entries) {
            val identities = NativeIdentityReservations()
            val first = identities.reserve("collision", direction)
            val conflict = runCatching { identities.reserve("collision", NativeCallDirection.OUTGOING) }.exceptionOrNull()
            assertTrue(conflict is ConversationFailure)
            assertEquals("invalid_arguments", (conflict as ConversationFailure).code)
            if (direction == NativeCallDirection.OUTGOING)
                assertTrue(runCatching { identities.reserve("collision", NativeCallDirection.INCOMING) }.isFailure)
            assertTrue(identities.isCurrent(first))
        }
    }

    @Test fun invalidAndReleasedIdentifiersNeverCorruptAnotherReservation() {
        val identities = NativeIdentityReservations()
        for (id in listOf("", " \n\t", "\u200B", "\u3000"))
            assertTrue(runCatching { identities.reserve(id, NativeCallDirection.OUTGOING) }.isFailure)
        val first = identities.reserve("reused", NativeCallDirection.OUTGOING)
        assertTrue(identities.release(first))
        val second = identities.reserve("reused", NativeCallDirection.OUTGOING)
        assertFalse(identities.release(first))
        assertFalse(identities.isCurrent(first))
        assertTrue(identities.isCurrent(second))
    }
}
