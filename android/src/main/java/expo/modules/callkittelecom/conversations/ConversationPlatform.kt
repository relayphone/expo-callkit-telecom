package expo.modules.callkittelecom.conversations

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** The system-reported battery policy for this app, not a guarantee of push delivery. */
enum class BackgroundRestriction {
    /** Battery optimization exemption with no explicit background restriction. */
    UNRESTRICTED,
    /** Normal battery optimizations apply. */
    OPTIMIZED,
    /** The system explicitly restricts this app's background work. */
    RESTRICTED,
}

/** Read-only Android platform status and settings destinations for the calling app. */
object ConversationPlatform {
    /** Reads current policy on each call. Explicit background restriction takes precedence. */
    @JvmStatic fun backgroundRestriction(context: Context): BackgroundRestriction =
        backgroundRestriction(object : BackgroundRestrictionState {
            override val sdkInt get() = Build.VERSION.SDK_INT
            override val ignoresBatteryOptimizations get() =
                context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
            override val backgroundRestricted get() =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        })

    /**
     * Returns this app's system settings screen; the person can open its battery controls there.
     * The app explains why unrestricted use helps calls and decides when to launch the intent.
     */
    @JvmStatic fun unrestrictedSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun backgroundRestriction(state: BackgroundRestrictionState): BackgroundRestriction = when {
        state.sdkInt >= Build.VERSION_CODES.P && state.backgroundRestricted -> BackgroundRestriction.RESTRICTED
        state.ignoresBatteryOptimizations -> BackgroundRestriction.UNRESTRICTED
        else -> BackgroundRestriction.OPTIMIZED
    }
}

internal interface BackgroundRestrictionState {
    val sdkInt: Int
    val ignoresBatteryOptimizations: Boolean
    val backgroundRestricted: Boolean
}
