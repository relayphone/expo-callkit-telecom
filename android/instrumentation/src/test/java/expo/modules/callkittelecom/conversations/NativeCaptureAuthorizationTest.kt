package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class NativeCaptureAuthorizationTest {
    internal class FakeAdm {
        var recordingEnabled = false
        var physicalReads = 0
        val events = mutableListOf<String>()
        val gate = NativeCaptureAuthorization({ enabled ->
            recordingEnabled = enabled; events += "recording=$enabled"
        }, { events += "statePublished" })

        fun restart(trigger: String): Int {
            events += "$trigger.restart"
            // Replay a new native thread observing the previous recording-enabled flag.
            recordingEnabled = true
            gate.enforceRecordingState()
            val bytes = if (recordingEnabled) 960 else 0
            if (bytes > 0) physicalReads++
            events += "$trigger.bytes=$bytes"
            return bytes
        }
    }

    @Test fun unauthorizedReadDiagnosticsResetPerGenerationAndNeverReuseTheFirstArm() {
        val gate = NativeCaptureAuthorization({}, {})
        assertEquals(0L, gate.unauthorizedRead(null, 10))
        assertNull(gate.unauthorizedRead(null, 11))
        assertTrue(gate.authorize(1, 20))
        assertTrue(gate.allows(1, 21))
        assertNull(gate.unauthorizedRead(1, 21))
        gate.revoke(2)
        assertFalse(gate.allows(1, 30))
        assertEquals(2L, gate.unauthorizedRead(null, 30))
        assertNull(gate.unauthorizedRead(null, 31))
        assertTrue(gate.authorize(3, 40))
        assertNull(gate.unauthorizedRead(3, 40))
        gate.revoke(4)
        assertEquals(4L, gate.unauthorizedRead(null, 50))
    }

    @Test fun closeAndARevocationBeforeFirstAuthorizationRejectDelayedEnablement() {
        val device = FakeAdm()
        device.gate.revoke(2)
        assertFalse(device.gate.authorize(1, 10))
        assertTrue(device.gate.authorize(3, 30))
        device.gate.close(4)
        assertFalse(device.gate.enableRecording(3))
        assertFalse(device.gate.authorize(5, 50))
        assertEquals(0, device.restart("closed"))
    }
}

@RunWith(Parameterized::class)
class NativeCaptureRestartPathTest(private val path: String) {
    @Test fun aHeldRestartCannotReadBeforeCurrentGenerationAuthorization() {
        val device = NativeCaptureAuthorizationTest.FakeAdm()
        assertTrue(device.gate.authorize(1, 100))
        assertTrue(device.gate.enableRecording(1))
        assertEquals(960, device.restart("active"))
        device.gate.revoke(2)
        val reads = device.physicalReads
        repeat(3) { assertEquals("Held restart from $path must not read the microphone", 0,
            device.restart(path)) }
        assertEquals("$path performed a physical read during hold", reads, device.physicalReads)
        assertFalse(device.gate.enableRecording(1))
        assertFalse(device.gate.authorize(1, 150))
        assertEquals(0, device.restart("staleResume"))
        assertTrue(device.gate.authorize(3, 200))
        assertTrue(device.gate.enableRecording(3))
        assertEquals(960, device.restart("authorizedResume"))
        assertTrue(device.events.indexOf("recording=false") < device.events.indexOf("$path.restart"))
        device.gate.revoke(4)
        assertFalse(device.gate.enableRecording(3))
        assertEquals("$path must not borrow the resumed generation's authority", 0, device.restart(path))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun restartPaths() = listOf("unpublish", "trackStop", "dispose", "setMicrophone", "admInitiated")
            .map { arrayOf(it) }
    }
}
