package expo.modules.callkittelecom.conversations

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

enum class NativeMediaStage(val value: String) {
    PUSH_RECEIVED("pushReceived"), REPORTED("reported"), PREWARM_STARTED("prewarmStarted"),
    ROOM_CONNECTED("roomConnected"), PREJOINED("prejoined"),
    JS_WARM_STARTED("jsWarmStarted"), ANSWER_REQUESTED("answerRequested"),
    ANSWER_ACCEPTED("answerAccepted"), AUDIO_WAIT_STARTED("audioWaitStarted"), AUDIO_WAIT_EXPIRED("audioWaitExpired"),
    ANSWER_FAILED("answerFailed"), AUDIO_AUTHORIZED("audioAuthorized"),
    FIRST_AUDIO("firstAudio"), CAPTURE_STARTED("captureStarted"),
    CAPTURE_STOPPED("captureStopped"), PLAYBACK_STARTED("playbackStarted"),
    PLAYBACK_STOPPED("playbackStopped"), REMOTE_SUBSCRIBED("remoteSubscribed"),
    REMOTE_UNSUBSCRIBED("remoteUnsubscribed"), CLOSED("closed"),
    TERMINAL_SIGNAL_COMPLETED("terminalSignalCompleted"),
    CORE_ADD_REQUESTED("coreAddRequested"), CORE_ADMITTED("coreAdmitted"),
    CREDENTIALS_STARTED("credentialsStarted"), CREDENTIALS_RESOLVED("credentialsResolved"),
    MEDIA_CONNECT_STARTED("mediaConnectStarted"), MEDIA_CONNECT_COMPLETED("mediaConnectCompleted"),
    ICE_CHECKING("iceChecking"), ICE_CONNECTED("iceConnected"), DTLS_CONNECTED("dtlsConnected"),
    TRANSPORT_CONNECTED("transportConnected"), SUBSCRIPTION_REQUESTED("subscriptionRequested"),
    REMOTE_TRACK_AVAILABLE("remoteTrackAvailable"), DECODER_FIRST_PCM("decoderFirstPcm"),
    MICROPHONE_PUBLISH_STARTED("microphonePublishStarted"), MICROPHONE_PUBLISHED("microphonePublished"),
    ROUTE_PREFERENCE_REQUESTED("routePreferenceRequested"), PREPARATION_READY("preparationReady"), AUDIO_MODE_READY("audioModeReady"), ENDPOINT_READY("endpointReady")
}

/** Native lifecycle observations contain timings/counts, never credentials or audio samples. */
data class NativeMediaSignal(val serverCallId: String, val stage: NativeMediaStage,
    val elapsedRealtimeNanos: Long, val measurements: Map<String, Long> = emptyMap())

fun interface NativeMediaObserver { fun onSignal(signal: NativeMediaSignal) }

/** Register during Application.onCreate. Delivery is on main, outside the audio callback. */
object NativeMediaSignals {
    val observers = CopyOnWriteArraySet<NativeMediaObserver>()
    private val main = Handler(Looper.getMainLooper())
    fun emit(serverCallId: String, stage: NativeMediaStage, measurements: Map<String, Long> = emptyMap(),
        observedAtNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        val signal = NativeMediaSignal(serverCallId, stage, observedAtNanos, measurements)
        main.post { observers.forEach { observer -> runCatching { observer.onSignal(signal) } } }
    }
}
