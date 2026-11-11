package expo.modules.callkittelecom.conversations

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPlatformJourneyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publicStatusReadsTheCallingAppsSystemServices() {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val expected = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activity.isBackgroundRestricted -> BackgroundRestriction.RESTRICTED
            power.isIgnoringBatteryOptimizations(context.packageName) -> BackgroundRestriction.UNRESTRICTED
            else -> BackgroundRestriction.OPTIMIZED
        }
        assertEquals(expected, ConversationPlatform.backgroundRestriction(context))
    }

    @Test fun settingsIntentTargetsThisAppsDetailsWithoutLaunchingAnything() {
        val intent = ConversationPlatform.unrestrictedSettingsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotNull(context.packageManager.resolveActivity(intent, 0))
    }
}
