package expo.modules.callkittelecom.conversations

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Counts real system full-screen deliveries in the instrumentation application. */
class PresentationRingingActivity : Activity() {
    companion object {
        var deliveries = 0
        private var active: PresentationRingingActivity? = null
        fun reset() { deliveries = 0 }
        fun close() { active?.finish() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setShowWhenLocked(true)
        active = this
        deliveries++
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); deliveries++ }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
}
