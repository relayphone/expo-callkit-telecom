package expo.modules.callkittelecom.conversations

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import expo.modules.callkittelecom.instrumentation.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTestIsolationTest {
    @Test fun activationRequiresTheDedicatedEmulatorTestBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val implementation = runCatching { Class.forName(
            "expo.modules.callkittelecom.conversations.EmulatorMediaHooks", false, context.classLoader) }
        if (BuildConfig.MEDIA_TEST_BUILD) {
            assertTrue(implementation.isSuccess)
            MediaTestRuntime.activate(context)
        } else {
            assertTrue(implementation.exceptionOrNull() is ClassNotFoundException)
            assertThrows(ClassNotFoundException::class.java) {
                Class.forName("expo.modules.callkittelecom.conversations.MediaApmCapture", false, context.classLoader)
            }
            val error = assertThrows(MediaHookUnavailable::class.java) { MediaTestRuntime.activate(context) }
            assertEquals(MediaHookFailure.UNSUPPORTED_BUILD, error.reason)
        }
        if (BuildConfig.BUILD_TYPE == "release") {
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        }
    }
}
