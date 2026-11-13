package expo.modules.callkittelecom.conversations

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import expo.modules.callkittelecom.instrumentation.BuildConfig

internal enum class MediaHookFailure { UNSUPPORTED_BUILD, NOT_EMULATOR, OUTPUT_TAP_UNAVAILABLE, UNSUPPORTED_PCM }
internal class MediaHookUnavailable(val reason: MediaHookFailure) : IllegalStateException(reason.name)

/** This guard belongs to the instrumentation host, never the published library. */
internal object MediaTestRuntime {
    fun activate(context: Context) {
        if (!BuildConfig.MEDIA_TEST_BUILD || !BuildConfig.DEBUG ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            throw MediaHookUnavailable(MediaHookFailure.UNSUPPORTED_BUILD)
        if (Build.HARDWARE !in setOf("ranchu", "goldfish") || !Build.PRODUCT.startsWith("sdk"))
            throw MediaHookUnavailable(MediaHookFailure.NOT_EMULATOR)
    }
}
