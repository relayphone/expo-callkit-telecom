package expo.modules.callkittelecom.conversations

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import androidx.core.telecom.CallEndpointCompat

internal interface ProximityWakeLock {
    fun acquire()
    fun release()
}

/** Main-thread ownership follows the active call and its observed Core endpoint. */
internal class ConversationProximity(private val lock: ProximityWakeLock?) {
    private var held = false

    fun update(active: Boolean, endpointType: Int?) {
        val needed = active && endpointType == CallEndpointCompat.TYPE_EARPIECE && lock != null
        if (held == needed) return
        if (needed) lock?.acquire() else lock?.release()
        held = needed
    }
}

internal fun proximityWakeLock(context: Context): ProximityWakeLock? {
    val power = context.getSystemService(PowerManager::class.java)
    if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return null
    val lock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Conversations:Proximity")
        .apply { setReferenceCounted(false) }
    return object : ProximityWakeLock {
        @SuppressLint("WakelockTimeout")
        override fun acquire() { lock.acquire() }
        override fun release() { lock.release() }
    }
}
