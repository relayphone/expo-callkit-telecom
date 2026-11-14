package expo.modules.callkittelecom.conversations

import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteAudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.junit.Assert.*
import org.junit.Test

class NativeAudioHooksTest {
    private val hooks = object : NativeAudioHooks {
        override val decodedSink = AudioTrackSink { _, _, _, _, _, _ -> }
        override fun capture(pacing: AudioBufferCallback) = pacing
        override fun configureOutput(builder: JavaAudioDeviceModule.Builder,
            observer: JavaAudioDeviceModule.PlaybackSamplesReadyCallback) = Unit
        override fun published(publication: LocalTrackPublication) = Unit
        override fun subscribed(track: RemoteAudioTrack) = Unit
        override fun close() = Unit
    }

    @Test fun instrumentationDefaultsRetainTheExactCaptureConfiguration() {
        val defaults = LocalAudioTrackOptions()
        assertTrue(defaults.noiseSuppression && defaults.echoCancellation)
        assertTrue(defaults.autoGainControl && defaults.highPassFilter)
        assertSame(defaults, hooks.captureOptions(defaults))
        val configured = defaults.copy(autoGainControl = false, highPassFilter = false)
        assertSame(configured, hooks.captureOptions(configured))
        assertNull(hooks.capturePostProcessor)
    }
}
