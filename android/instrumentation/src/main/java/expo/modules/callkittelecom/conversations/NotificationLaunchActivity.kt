package expo.modules.callkittelecom.conversations

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Real launcher for native notification-intent journeys, without React or JavaScript. */
open class NotificationLaunchActivity : Activity() {
    companion object {
        var active: NotificationLaunchActivity? = null
        var creations = 0
        var resumes = 0
        var answers = 0
        var lastIntent: Intent? = null
        var lastClass: String? = null
        fun reset() { creations = 0; resumes = 0; answers = 0; lastIntent = null; lastClass = null }
    }
    private fun receive(value: Intent, phase: AnswerJourneyPhase) {
        val token = AnswerJourneyObservation.trace?.intent(phase, value)
        lastIntent = Intent(value)
        lastClass = javaClass.name
        if (ConversationNotificationIntents.receive(this, value)) {
            answers++
            AnswerJourneyObservation.trace?.consumed(token)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        active = this; creations++
        receive(intent, AnswerJourneyPhase.ACTIVITY_CREATED)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); receive(intent, AnswerJourneyPhase.ACTIVITY_NEW_INTENT)
    }
    override fun onResume() {
        super.onResume(); active = this; resumes++
        AnswerJourneyObservation.trace?.intent(AnswerJourneyPhase.ACTIVITY_RESUMED, intent)
    }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
}

class NotificationAlternateActivity : NotificationLaunchActivity()
