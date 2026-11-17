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
        validate(BuildConfig.MEDIA_TEST_BUILD, BuildConfig.DEBUG,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, Build.HARDWARE, Build.PRODUCT)
    }
    fun validate(testBuild: Boolean, debug: Boolean, debuggable: Boolean, hardware: String, product: String) {
        if (!testBuild || !debug || !debuggable)
            throw MediaHookUnavailable(MediaHookFailure.UNSUPPORTED_BUILD)
        if (hardware !in setOf("ranchu", "goldfish") || !product.startsWith("sdk"))
            throw MediaHookUnavailable(MediaHookFailure.NOT_EMULATOR)
    }
}
