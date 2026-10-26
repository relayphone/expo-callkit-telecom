package expo.modules.callkittelecom.conversations

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

enum class NativeMediaStage(val value: String) {
    PUSH_RECEIVED("pushReceived"), REPORTED("reported"), PREWARM_STARTED("prewarmStarted"), PREJOINED("prejoined"),
    JS_WARM_STARTED("jsWarmStarted"), ANSWER_REQUESTED("answerRequested"),
    ANSWER_ACCEPTED("answerAccepted"), AUDIO_AUTHORIZED("audioAuthorized"),
    FIRST_AUDIO("firstAudio"), CAPTURE_STARTED("captureStarted"),
    CAPTURE_STOPPED("captureStopped"), PLAYBACK_STARTED("playbackStarted"),
    PLAYBACK_STOPPED("playbackStopped"), REMOTE_SUBSCRIBED("remoteSubscribed"),
    REMOTE_UNSUBSCRIBED("remoteUnsubscribed"), CLOSED("closed")
}

/** Native lifecycle observations contain timings/counts, never credentials or audio samples. */
data class NativeMediaSignal(val serverCallId: String, val stage: NativeMediaStage,
    val elapsedRealtimeNanos: Long, val measurements: Map<String, Long> = emptyMap())

fun interface NativeMediaObserver { fun onSignal(signal: NativeMediaSignal) }

/** Register during Application.onCreate. Delivery is on main, outside the audio callback. */
object NativeMediaSignals {
    val observers = CopyOnWriteArraySet<NativeMediaObserver>()
    private val main = Handler(Looper.getMainLooper())
    fun emit(serverCallId: String, stage: NativeMediaStage, measurements: Map<String, Long> = emptyMap()) {
        val signal = NativeMediaSignal(serverCallId, stage, SystemClock.elapsedRealtimeNanos(), measurements)
        main.post { observers.forEach { observer -> runCatching { observer.onSignal(signal) } } }
    }
}
