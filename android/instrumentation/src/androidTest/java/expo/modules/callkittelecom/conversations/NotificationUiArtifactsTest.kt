package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationUiArtifactsTest {
    @get:Rule val uiArtifacts: org.junit.rules.TestRule = NotificationUiArtifactsRule()
    @Test fun aMissingSyntheticCardTimesOutAndRetainsBothArtifacts() = runBlocking {
        val directory = notificationUiArtifactDirectory()
        val before = directory.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        try {
            withTimeout(5000) { prepareNotificationTestDisplay() }
            val failure = runCatching { withTimeout(500) { tapNotificationTestCard("missing-synthetic-notification") } }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            captureNotificationUiFailure()
            val added = directory.listFiles().orEmpty().filter { it.name !in before }
            assertEquals("Expected one hierarchy despite duplicate timeout capture", 1, added.count { it.extension == "xml" && it.length() > 0 })
            assertEquals("Expected one screenshot despite duplicate timeout capture", 1, added.count { it.extension == "png" && it.length() > 0 })
        } finally { withTimeout(5000) { collapseNotificationTestShade() } }
    }
}
