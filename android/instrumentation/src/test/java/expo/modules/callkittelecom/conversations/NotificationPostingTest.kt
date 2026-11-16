package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test

class NotificationPostingTest {
    @Test fun acceptedNotificationIsNotRecordedAsRefused() {
        var posts = 0
        assertTrue(postNotification({ posts++ }, { error("Unexpected refusal") }))
        assertEquals(1, posts)
    }

    @Test fun platformRefusalsAreRecordedWithoutEscaping() {
        for ((error, reason) in listOf(
            IllegalArgumentException("CallStyle is not eligible") to NotificationPostRefusal.INVALID_NOTIFICATION,
            SecurityException("Permission revoked") to NotificationPostRefusal.PERMISSION_DENIED)) {
            val refusals = mutableListOf<NotificationPostRefusal>()
            assertFalse(postNotification({ throw error }, { refusals += it }))
            assertEquals(listOf(reason), refusals)
        }
    }

    @Test fun unrelatedFailuresStillEscape() {
        val error = IllegalStateException("Unrelated failure")
        assertSame(error, runCatching { postNotification({ throw error }, { fail("Unexpected refusal") }) }.exceptionOrNull())
    }
}
