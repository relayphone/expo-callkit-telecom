package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test

class PlatformCallRemainingTest {
    @Test fun removalFailurePreservesTheLogicalTimestampReasonAndFailureKind() {
        for (kind in listOf(EndedFailure.MEDIA, EndedFailure.SERVER, EndedFailure.FRAMEWORK_RESET)) {
            val logical = conversationEnd("2026-11-14T00:00:00Z", "failed", kind)
            val remaining = withRemainingPlatformCall(logical)
            assertEquals(logical["at"], remaining["at"])
            assertEquals(logical["reason"], remaining["reason"])
            assertEquals(mapOf("kind" to kind.kind, "platformCallRemaining" to true), remaining["failure"])
            assertEquals(mapOf("kind" to kind.kind), logical["failure"])
            assertEquals(remaining, withRemainingPlatformCall(remaining))
        }
    }

    @Test fun aRemovalDeadlineDoesNotInventAFailureForANonfailedOrUnknownEnd() {
        for (reason in listOf("local", "remoteEnded", "unanswered", "answeredElsewhere", "declinedElsewhere", "failed")) {
            val logical = conversationEnd("at", reason, null)
            assertSame(logical, withRemainingPlatformCall(logical))
        }
    }
}
