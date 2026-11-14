package expo.modules.callkittelecom.conversations

import io.livekit.android.room.track.LocalAudioTrackOptions

/** Identification-only AEC probe for the synthetic source; device capture retains the SDK defaults. */
internal object EmulatorCaptureOptions {
    fun from(defaults: LocalAudioTrackOptions): LocalAudioTrackOptions =
        defaults.copy(noiseSuppression = false, echoCancellation = false)
}
