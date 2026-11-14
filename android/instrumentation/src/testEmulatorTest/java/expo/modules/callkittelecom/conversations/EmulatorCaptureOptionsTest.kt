package expo.modules.callkittelecom.conversations

import io.livekit.android.room.track.LocalAudioTrackOptions
import org.junit.Assert.*
import org.junit.Test

class EmulatorCaptureOptionsTest {
    @Test fun identificationProbeDisablesOnlyNoiseSuppressionAndEchoCancellation() {
        val defaults = LocalAudioTrackOptions()
        val options = EmulatorCaptureOptions.from(defaults)
        assertFalse(options.noiseSuppression)
        assertFalse(options.echoCancellation)
        assertTrue(options.autoGainControl)
        assertTrue(options.highPassFilter)
        assertTrue(options.typingNoiseDetection)
        assertTrue(defaults.noiseSuppression)
        assertTrue(defaults.echoCancellation)
    }

    @Test fun unrelatedCaptureDefaultsArePreserved() {
        val defaults = LocalAudioTrackOptions(noiseSuppression = true, echoCancellation = true,
            autoGainControl = false, highPassFilter = false, typingNoiseDetection = false)
        assertEquals(defaults.copy(noiseSuppression = false, echoCancellation = false), EmulatorCaptureOptions.from(defaults))
        val disabled = defaults.copy(noiseSuppression = false, echoCancellation = false)
        assertEquals(disabled, EmulatorCaptureOptions.from(disabled))
    }
}
