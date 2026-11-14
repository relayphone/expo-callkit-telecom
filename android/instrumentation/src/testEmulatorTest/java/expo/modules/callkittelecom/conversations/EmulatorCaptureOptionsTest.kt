package expo.modules.callkittelecom.conversations

import io.livekit.android.room.track.LocalAudioTrackOptions
import org.junit.Assert.*
import org.junit.Test

class EmulatorCaptureOptionsTest {
    @Test fun syntheticCaptureDisablesAcousticProcessingAndKeepsGainAndHighPassOn() {
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

    @Test fun syntheticProcessingIsExplicitAndUnrelatedDefaultsArePreserved() {
        val defaults = LocalAudioTrackOptions(noiseSuppression = true, echoCancellation = true,
            autoGainControl = false, highPassFilter = false, typingNoiseDetection = false)
        val expected = defaults.copy(noiseSuppression = false, echoCancellation = false,
            autoGainControl = true, highPassFilter = true)
        assertEquals(expected, EmulatorCaptureOptions.from(defaults))
        assertEquals(expected, EmulatorCaptureOptions.from(expected))
    }
}
