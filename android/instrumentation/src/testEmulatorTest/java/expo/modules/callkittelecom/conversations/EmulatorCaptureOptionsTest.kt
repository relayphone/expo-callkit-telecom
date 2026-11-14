package expo.modules.callkittelecom.conversations

import io.livekit.android.room.track.LocalAudioTrackOptions
import org.junit.Assert.*
import org.junit.Test

class EmulatorCaptureOptionsTest {
    @Test fun syntheticCaptureDisablesOnlyNoiseSuppression() {
        val defaults = LocalAudioTrackOptions()
        val options = EmulatorCaptureOptions.from(defaults)
        assertFalse(options.noiseSuppression)
        assertTrue(options.echoCancellation)
        assertTrue(options.autoGainControl)
        assertTrue(options.highPassFilter)
        assertTrue(options.typingNoiseDetection)
        assertTrue(defaults.noiseSuppression)
    }

    @Test fun unrelatedCaptureDefaultsArePreserved() {
        val defaults = LocalAudioTrackOptions(noiseSuppression = true, echoCancellation = false,
            autoGainControl = false, highPassFilter = false, typingNoiseDetection = false)
        assertEquals(defaults.copy(noiseSuppression = false), EmulatorCaptureOptions.from(defaults))
        val disabled = defaults.copy(noiseSuppression = false)
        assertEquals(disabled, EmulatorCaptureOptions.from(disabled))
    }
}
