package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.Application
import java.util.concurrent.atomic.AtomicInteger

internal object MediaPermissionObservation {
    val microphoneChecks = AtomicInteger()
}

/** Counts permission queries in the instrumentation application without changing their result. */
class MediaPermissionObservationApplication : Application() {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        if (permission == Manifest.permission.RECORD_AUDIO) MediaPermissionObservation.microphoneChecks.incrementAndGet()
        return super.checkPermission(permission, pid, uid)
    }

    override fun checkSelfPermission(permission: String): Int {
        if (permission == Manifest.permission.RECORD_AUDIO) MediaPermissionObservation.microphoneChecks.incrementAndGet()
        return super.checkSelfPermission(permission)
    }
}
