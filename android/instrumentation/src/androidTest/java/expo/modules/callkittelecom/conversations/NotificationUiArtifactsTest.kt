package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationUiArtifactsTest {
    @Test fun aMissingSyntheticCardTimesOutAndRetainsBothArtifacts() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "systemui-failures")
        val before = directory.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        try {
            withTimeout(5000) { prepareNotificationTestDisplay() }
            val failure = runCatching { withTimeout(500) { tapNotificationTestCard("missing-synthetic-notification") } }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            val added = directory.listFiles().orEmpty().filter { it.name !in before }
            assertTrue("Missing hierarchy", added.any { it.extension == "xml" && it.length() > 0 })
            assertTrue("Missing screenshot", added.any { it.extension == "png" && it.length() > 0 })
        } finally { withTimeout(5000) { collapseNotificationTestShade() } }
    }
}
