package expo.modules.callkittelecom.conversations

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MediaGateTraceTest {
    private var clock = 100L
    private fun tap() = MediaGateTrace { clock++ }.apply { arm(MediaGateTrace.Phase.HELD) }
    private fun enter(tap: MediaGateTrace, closed: Boolean?, first: Long = 0) = tap.enter(
        MediaGateTrace.Direction.PLAYBACK, 7, clock++, closed, first, 480, 48000, 1)

    @Test fun aFailingDiagnosticAlwaysStopsRecordingAndRetainsItsCompletedPrefix() = runBlocking {
        val tap = MediaGateTrace { clock++ }
        val expected = IllegalStateException("diagnostic control failed")
        try {
            tap.recording(MediaGateTrace.Phase.MUTED) {
                tap.exit(enter(tap, true), ByteArray(960))
                yield()
                throw expected
            }
            fail("Expected the diagnostic failure")
        } catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertFalse(tap.isArmed)
        assertNull(enter(tap, false))
        assertEquals(1, tap.snapshot().getJSONArray("blocks").length())
    }

    @Test fun retainsOpenEntryEvenWhenTheGateClosesAndThePhaseAcknowledgesBeforeExit() {
        val tap = tap()
        val first = enter(tap, false)
        tap.control(NativeControlObservation("call", NativeControlKind.HOLD, true,
            NativeControlPoint.PLATFORM_ACKNOWLEDGED, clock++))
        tap.phaseAcknowledged(7)
        val samples = ByteArray(960).apply { this[0] = 4; this[2] = -4; this[3] = -1 }
        val unchanged = samples.copyOf()
        assertEquals(1, tap.snapshot().getJSONArray("inFlight").length())
        tap.exit(first, samples)
        assertArrayEquals(unchanged, samples)
        samples.fill(0)
        val closed = enter(tap, true, 480)
        tap.exit(closed, samples)
        val report = tap.snapshot()
        assertTrue(report.isNull("error")); assertFalse(report.getBoolean("overflow"))
        assertEquals(0, report.getJSONArray("inFlight").length())
        val blocks = report.getJSONArray("blocks")
        assertFalse(blocks.getJSONArray(0).getBoolean(6)); assertTrue(blocks.getJSONArray(1).getBoolean(6))
        assertEquals(4, blocks.getJSONArray(0).getInt(11))
        assertEquals(kotlin.math.sqrt(32.0 / 480), blocks.getJSONArray(0).getDouble(12), 1e-12)
        assertEquals(0, blocks.getJSONArray(1).getInt(11))
        val retained = Base64.getDecoder().decode(report.getString("pcm"))
        assertEquals(1920, retained.size)
        assertArrayEquals(unchanged, retained.copyOfRange(0, 960))
        assertTrue(blocks.getJSONArray(0).getLong(3) < blocks.getJSONArray(0).getLong(5))
        assertTrue(report.getJSONArray("events").toString().contains("PLAYBACK_CLOSED_AT_ENTRY"))
    }

    @Test fun closedEntryNonzeroPcmRemainsVisibleAndNoPhaseBoundaryIsChanged() {
        val tap = tap()
        val pcm = ByteArray(960).apply { this[0] = 100 }
        tap.phaseAcknowledged(3)
        tap.exit(enter(tap, true), pcm)
        val report = tap.snapshot()
        assertEquals(100, report.getJSONArray("blocks").getJSONArray(0).getInt(11))
        assertEquals(7, report.getJSONArray("blocks").getJSONArray(0).getInt(2))
        assertEquals(3, report.getJSONArray("events").getJSONObject(1).getLong("phaseId"))
        tap.stop()
        assertNull(enter(tap, false))
    }

    @Test fun boundsPcmAndMetadataAndReportsUnknownGateAndFormatWithoutWritingInput() {
        val tap = tap(); val pcm = ByteArray(960)
        tap.exit(enter(tap, null), pcm)
        repeat(600) { index -> tap.exit(enter(tap, true, index * 480L), pcm) }
        val report = tap.snapshot()
        assertEquals("GATE_UNAVAILABLE", report.getString("error"))
        assertTrue(report.getBoolean("overflow"))
        assertEquals(512, report.getJSONArray("blocks").length())
        assertTrue(report.toString().length < 900000)
        val invalid = tap()
        assertNull(invalid.enter(MediaGateTrace.Direction.CAPTURE, 1, clock++, false, 0, 80, 8000, 1))
        assertEquals("FORMAT", invalid.snapshot().getString("error"))
    }
}
