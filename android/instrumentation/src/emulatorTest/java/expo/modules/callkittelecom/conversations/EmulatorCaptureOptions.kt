package expo.modules.callkittelecom.conversations

import io.livekit.android.room.track.LocalAudioTrackOptions

/** No acoustic path exists for synthetic capture; device capture retains the SDK defaults. */
internal object EmulatorCaptureOptions {
    fun from(defaults: LocalAudioTrackOptions): LocalAudioTrackOptions = defaults.copy(
        noiseSuppression = false, echoCancellation = false, autoGainControl = true, highPassFilter = true)
}
