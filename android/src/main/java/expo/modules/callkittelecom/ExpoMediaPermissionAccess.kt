package expo.modules.callkittelecom

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.modules.core.PermissionAwareActivity
import expo.modules.callkittelecom.conversations.ConversationFailure
import expo.modules.callkittelecom.conversations.MediaKind
import expo.modules.callkittelecom.conversations.MediaPermissionAccess
import expo.modules.callkittelecom.conversations.MediaPermissionResponse
import expo.modules.callkittelecom.conversations.MediaPermissionStatus
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.AppContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Use Expo's persisted request/blocked history rather than maintaining another permission cache. */
internal class ExpoMediaPermissionAccess(private val appContext: AppContext) : MediaPermissionAccess {
    private val MediaKind.permission: String get() = when (this) {
        MediaKind.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        MediaKind.CAMERA -> Manifest.permission.CAMERA
    }

    override fun isForeground(): Boolean {
        val activity = appContext.currentActivity ?: return false
        return !activity.isFinishing && !activity.isDestroyed && activity is PermissionAwareActivity &&
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
    }

    override suspend fun read(): Map<MediaKind, MediaPermissionResponse> = suspendCancellableCoroutine { continuation ->
        val context = requireNotNull(appContext.reactContext)
        requireNotNull(appContext.permissions).getPermissions({ responses ->
            val result = MediaKind.entries.associateWith { kind ->
                val response = responses.getValue(kind.permission)
                val granted = ContextCompat.checkSelfPermission(context, kind.permission) == PackageManager.PERMISSION_GRANTED
                val status = when (response.status) {
                    PermissionsStatus.UNDETERMINED -> MediaPermissionStatus.UNDETERMINED
                    PermissionsStatus.DENIED -> MediaPermissionStatus.DENIED
                    PermissionsStatus.GRANTED -> MediaPermissionStatus.GRANTED
                }
                MediaPermissionResponse.from(granted, status, response.canAskAgain)
            }
            if (continuation.isActive) continuation.resume(result)
        }, *MediaKind.entries.map { it.permission }.toTypedArray())
    }

    override suspend fun request(kinds: List<MediaKind>): Unit = suspendCancellableCoroutine { continuation ->
        if (!isForeground()) throw ConversationFailure("not_foreground")
        requireNotNull(appContext.permissions).askForPermissions({
            if (continuation.isActive) continuation.resume(Unit)
        }, *kinds.map { it.permission }.toTypedArray())
    }
}
