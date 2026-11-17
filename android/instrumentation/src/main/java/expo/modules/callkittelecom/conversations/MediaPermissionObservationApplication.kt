package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Application
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

internal object MediaPermissionObservation {
    val microphoneChecks = AtomicInteger()
    val cameraChecks = AtomicInteger()
    val forbidCamera = AtomicBoolean()
    fun checked(permission: String) {
        if (permission == Manifest.permission.RECORD_AUDIO) microphoneChecks.incrementAndGet()
        if (permission == Manifest.permission.CAMERA) {
            cameraChecks.incrementAndGet()
            check(!forbidCamera.get()) { "Camera permission path invoked" }
        }
    }
}

/** Counts permission queries in the instrumentation application without changing their result. */
class MediaPermissionObservationApplication : Application() {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        MediaPermissionObservation.checked(permission)
        return super.checkPermission(permission, pid, uid)
    }

    override fun checkSelfPermission(permission: String): Int {
        MediaPermissionObservation.checked(permission)
        return super.checkSelfPermission(permission)
    }
}
