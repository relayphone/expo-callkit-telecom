package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaSourceReadinessTest {
    @Test fun waitsForAValidBufferAndRecordsTheOriginalSignalTimes() = runBlocking {
        var now = 1_000_000_000L
        val source = MediaSourceReadiness { now }
        source.published(now); source.pumpStarted(now + 700_000_000)
        var pauses = 0
        source.awaitReady {
            pauses++
            now += 100_000_000
            source.buffer(now, if (pauses < 8) 479 else 480, 48000, 0, true)
        }
        assertEquals(8, pauses)
        val saved = source.snapshot()
        assertEquals("1000000000", saved.getString("publicationNs"))
        assertEquals("1700000000", saved.getString("pumpStartedNs"))
        assertEquals("1800000000", saved.getString("firstBufferNs"))
        assertEquals(800.0, saved.getDouble("startLatencyMs"), 0.0)
        assertEquals(3300, saved.getInt("budgetMs"))
        assertEquals("700000000", saved.getString("publicationToPumpNs"))
        assertEquals(700.0, saved.getDouble("publicationToPumpMs"), 0.0)
        assertEquals("100000000", saved.getString("pumpToFirstBufferNs"))
        assertEquals(100.0, saved.getDouble("pumpToFirstBufferMs"), 0.0)
        assertFalse(saved.getBoolean("timedOut"))
    }

    @Test fun callbackBeforeMainReceivesPublicationIsRetainedWithItsOwnTime() = runBlocking {
        val source = MediaSourceReadiness { 3_000_000_000 }
        source.buffer(999_999_999, 480, 48000, 0, true)
        source.buffer(1_100_000_000, 480, 48000, 0, true)
        source.pumpStarted(1_050_000_000); source.published(1_000_000_000)
        source.awaitReady { fail("the on-time buffer is already present") }
        assertEquals("1100000000", source.snapshot().getString("firstBufferNs"))
        assertEquals(100.0, source.snapshot().getDouble("startLatencyMs"), 0.0)
    }

    @Test fun theExact3300MillisecondBoundaryIsAcceptedButOneNanosecondLateFails() = runBlocking {
        for (late in listOf(0L, 1L)) {
            val source = MediaSourceReadiness { 4_300_000_001 }
            source.published(1_000_000_000); source.pumpStarted(1_100_000_000)
            source.buffer(4_300_000_000 + late, 480, 48000, 0, true)
            val error = runCatching { source.awaitReady { fail("deadline already passed") } }.exceptionOrNull()
            if (late == 0L) assertNull(error)
            else { assertTrue(error is AssertionError); assertEquals("source-start-timeout", error!!.message) }
            val saved = source.snapshot()
            assertEquals("100000000", saved.getString("publicationToPumpNs"))
            assertEquals((3_200_000_000L + late).toString(), saved.getString("pumpToFirstBufferNs"))
            assertEquals(late != 0L, saved.getBoolean("timedOut"))
        }
    }

    @Test fun missingObservationsStayUnknownInTheStartupTrace() {
        val source = MediaSourceReadiness { 1_000_000_000 }
        var saved = source.snapshot()
        assertTrue(saved.isNull("publicationToPumpNs"))
        assertTrue(saved.isNull("publicationToPumpMs"))
        assertTrue(saved.isNull("pumpToFirstBufferNs"))
        assertTrue(saved.isNull("pumpToFirstBufferMs"))
        source.published(1_000_000_000); source.pumpStarted(1_100_000_000)
        saved = source.snapshot()
        assertEquals(100.0, saved.getDouble("publicationToPumpMs"), 0.0)
        assertTrue(saved.isNull("pumpToFirstBufferNs"))
        assertTrue(saved.isNull("pumpToFirstBufferMs"))
    }

    @Test fun physicalUnmutedWrongSizedAndWrongRateBuffersCannotSatisfyReadiness() = runBlocking {
        var now = 1_000_000_000L
        val source = MediaSourceReadiness { now }
        source.published(now); source.pumpStarted(now)
        source.buffer(now, 480, 48000, 960, true)
        source.buffer(now, 480, 48000, 0, false)
        source.buffer(now, 480, 48000, 0, null)
        source.buffer(now, 479, 48000, 0, true)
        source.buffer(now, 480, 8000, 0, true)
        val error = runCatching { source.awaitReady { now += 1_500_000_000 } }.exceptionOrNull()
        assertEquals("source-start-timeout", error?.message)
        assertTrue(source.snapshot().getBoolean("timedOut"))
        assertTrue(source.snapshot().isNull("firstBufferNs"))
    }

    @Test fun anOuterCancellationRemainsCancellation() = runBlocking {
        val source = MediaSourceReadiness { 1_000_000_000 }
        source.published(1_000_000_000)
        val error = runCatching { withTimeout(20) { source.awaitReady { awaitCancellation() } } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertFalse(source.snapshot().getBoolean("timedOut"))
    }
}
