package expo.modules.callkittelecom.conversations

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

enum class NativeMediaStage(val value: String) {
    PUSH_RECEIVED("pushReceived"), PUSH_REFUSED("pushRefused"), REPORTED("reported"), PREWARM_STARTED("prewarmStarted"),
    ROOM_CONNECTED("roomConnected"), ROOM_DISCONNECTED("roomDisconnected"), PREJOINED("prejoined"), PREWARM_LOST("prewarmLost"),
    PREWARM_FAILED("prewarmFailed"),
    PREWARM_RECEIVER_WARMUP_SKIPPED("prewarmReceiverWarmupSkipped"),
    JS_WARM_STARTED("jsWarmStarted"), ANSWER_REQUESTED("answerRequested"),
    HANDLER_STARTED("handlerStarted"), HANDLER_RESPONDED("handlerResponded"), CONNECTED("connected"),
    MEDIA_PROVIDED("mediaProvided"), MEDIA_IGNORED("mediaIgnored"), MEDIA_ACTIVATED("mediaActivated"),
    RECONNECTING("reconnecting"), RECONNECTED("reconnected"),
    ANSWER_ACCEPTED("answerAccepted"), AUDIO_WAIT_STARTED("audioWaitStarted"), AUDIO_WAIT_EXPIRED("audioWaitExpired"),
    ANSWER_FAILED("answerFailed"), AUDIO_AUTHORIZED("audioAuthorized"),
    FIRST_AUDIO("firstAudio"), CAPTURE_STARTED("captureStarted"),
    CAPTURE_STOPPED("captureStopped"), PLAYBACK_STARTED("playbackStarted"),
    PLAYBACK_STOPPED("playbackStopped"), REMOTE_SUBSCRIBED("remoteSubscribed"),
    REMOTE_UNSUBSCRIBED("remoteUnsubscribed"), CLOSED("closed"),
    CLOSE_REQUESTED("closeRequested"), CLOSE_LIFETIME_JOINED("closeLifetimeJoined"),
    CLOSE_SESSION_COMPLETED("closeSessionCompleted"), CLOSE_OBSERVATION_DRAINED("closeObservationDrained"),
    CLOSE_DISCONNECT_EMITTED("closeDisconnectEmitted"), CLOSE_TRACKS_REMOVED("closeTracksRemoved"),
    CLOSE_AUDIO_HOOKS_CLOSED("closeAudioHooksClosed"), CLOSE_ROOM_RELEASED("closeRoomReleased"),
    RELEASE_COMPLETED("releaseCompleted"),
    TERMINAL_SIGNAL_COMPLETED("terminalSignalCompleted"),
    CORE_ADD_REQUESTED("coreAddRequested"), CORE_ADMITTED("coreAdmitted"), CORE_LATE_DISCONNECTED("coreLateDisconnected"),
    CORE_MUTE_OBSERVED("coreMuteObserved"),
    MUTE_SERIAL_ENTERED("muteSerialEntered"), MUTE_SERIAL_EXITED("muteSerialExited"),
    MUTE_SESSION_ENTERED("muteSessionEntered"), MUTE_SESSION_EXITED("muteSessionExited"),
    MUTE_PUBLICATION_ENTERED("mutePublicationEntered"), MUTE_PUBLICATION_EXITED("mutePublicationExited"),
    MUTE_BOUNDARY_ENTERED("muteBoundaryEntered"), MUTE_BOUNDARY_EXITED("muteBoundaryExited"),
    PLATFORM_REMOVAL_FAILED("platformRemovalFailed"),
    CREDENTIALS_STARTED("credentialsStarted"), CREDENTIALS_RESOLVED("credentialsResolved"),
    MEDIA_CONNECT_STARTED("mediaConnectStarted"), MEDIA_CONNECT_COMPLETED("mediaConnectCompleted"),
    ICE_CHECKING("iceChecking"), ICE_CONNECTED("iceConnected"), DTLS_CONNECTED("dtlsConnected"),
    TRANSPORT_CONNECTED("transportConnected"), SUBSCRIPTION_REQUESTED("subscriptionRequested"),
    REMOTE_TRACK_AVAILABLE("remoteTrackAvailable"), DECODER_FIRST_PCM("decoderFirstPcm"),
    MICROPHONE_PUBLISH_STARTED("microphonePublishStarted"), MICROPHONE_PUBLISHED("microphonePublished"),
    ROUTE_PREFERENCE_REQUESTED("routePreferenceRequested"), PREPARATION_READY("preparationReady"),
    AUDIO_MODE_READY("audioModeReady"), ENDPOINT_READY("endpointReady"),
    CAPTURE_BEFORE_AUTHORITY("captureBeforeAuthority"), CAPTURE_RETIREMENT("captureRetirement"),
    CAPTURE_PUMP_STARTED("capturePumpStarted"), CAPTURE_PUMP_STOPPED("capturePumpStopped"),
    OUTPUT_BEFORE_AUTHORITY("outputBeforeAuthority"), OUTPUT_FIRST_NONZERO("outputFirstNonzero"),
    OUTPUT_MUTED("outputMuted"), OUTPUT_OBSERVATION_UNAVAILABLE("outputObservationUnavailable"),
    OUTPUT_RELEASED("outputReleased"), OUTPUT_MUTE_BOUNDARY_MISSED("outputMuteBoundaryMissed")
}

/** Native observations contain no credentials/audio. ROOM_DISCONNECTED has an empty serverCallId and a reason name.
 * MUTE_* pairs share an operationId. EXITED has completed=1 only on normal return; elapsedRealtimeNanos
 * is captured at the work boundary, before delivery on main. SERIAL covers acquisition, not lock ownership.
 * CLOSED marks the session cleanup return; RELEASE_COMPLETED marks settlement of the controller's
 * release deferred, including failure. CLOSE_* durations share an operationId and include failed subcalls.
 */
data class NativeMediaSignal(val serverCallId: String, val stage: NativeMediaStage,
    val elapsedRealtimeNanos: Long, val measurements: Map<String, Long> = emptyMap(), val reason: String? = null,
    val prewarmFailure: NativePrewarmFailure? = null)

fun interface NativeMediaObserver { fun onSignal(signal: NativeMediaSignal) }

/** Register during Application.onCreate. Delivery is on main, outside the audio callback. */
object NativeMediaSignals {
    val observers = CopyOnWriteArraySet<NativeMediaObserver>()
    private val main = Handler(Looper.getMainLooper())
    fun emit(serverCallId: String, stage: NativeMediaStage, measurements: Map<String, Long> = emptyMap(),
        observedAtNanos: Long = SystemClock.elapsedRealtimeNanos(), reason: String? = null,
        prewarmFailure: NativePrewarmFailure? = null) {
        val signal = NativeMediaSignal(if (stage == NativeMediaStage.ROOM_DISCONNECTED) "" else serverCallId,
            stage, observedAtNanos, measurements, reason, prewarmFailure)
        main.post { observers.forEach { observer -> runCatching { observer.onSignal(signal) } } }
    }
}
