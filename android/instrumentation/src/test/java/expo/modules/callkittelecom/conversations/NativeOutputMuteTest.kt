package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeOutputMuteTest {
    @Test fun aBufferFilledBeforeHoldFinishesBeforeTheGateCloses() = runBlocking {
        val gate = NativeOutputMute()
        gate.playbackStarted()
        var speakerMuted = false
        // The SDK has filled PCM with its gate open, but its blocking write has not completed.
        val pendingPcm = shortArrayOf(512, -512)
        val hold = async(start = CoroutineStart.UNDISPATCHED) {
            gate.atPlaybackBoundary { speakerMuted = true }
        }
        assertFalse("The in-flight block must enter its callback before the gate closes", speakerMuted)
        assertFalse("Hold must await application on the playback thread", hold.isCompleted)
        assertArrayEquals(shortArrayOf(512, -512), pendingPcm)
        gate.callbackCompleted()
        hold.await()
        assertTrue(speakerMuted)
        // The following SDK loop sees the closed gate before writing its buffer.
        val nextPcm = shortArrayOf(512, -512).apply { if (speakerMuted) fill(0) }
        assertArrayEquals(shortArrayOf(0, 0), nextPcm)
        assertArrayEquals("The previous callback remains unchanged", shortArrayOf(512, -512), pendingPcm)
    }

    @Test fun cancelledAndSupersededRequestsCannotApplyALateMute() = runBlocking {
        val gate = NativeOutputMute()
        gate.playbackStarted()
        var applications = 0
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            gate.atPlaybackBoundary { applications++ }
        }
        cancelled.cancel()
        gate.callbackCompleted()
        cancelled.join()
        assertEquals(0, applications)

        val superseded = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.atPlaybackBoundary { applications++ } }
        }
        gate.cancelPending()
        assertTrue(superseded.await().exceptionOrNull() is CancellationException)
        gate.callbackCompleted()
        assertEquals(0, applications)

        val current = async(start = CoroutineStart.UNDISPATCHED) {
            gate.atPlaybackBoundary { applications++ }
        }
        gate.callbackCompleted(); current.await(); gate.callbackCompleted()
        assertEquals(1, applications)
    }

    @Test fun setterFailureReachesTheCallerWithoutEscapingTheAudioCallback() = runBlocking {
        val gate = NativeOutputMute()
        gate.playbackStarted()
        val expected = IllegalStateException("Output gate failed")
        val hold = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.atPlaybackBoundary { throw expected } }
        }
        gate.callbackCompleted()
        val actual = hold.await().exceptionOrNull()
        assertNotNull(actual)
        assertEquals(expected.javaClass, actual!!.javaClass)
        assertEquals(expected.message, actual.message)
    }

    @Test fun closingCancelsAnUnappliedRequestAndRejectsNewRequests() = runBlocking {
        val gate = NativeOutputMute()
        gate.playbackStarted()
        var applied = false
        val hold = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.atPlaybackBoundary { applied = true } }
        }
        gate.close()
        assertTrue(hold.await().exceptionOrNull() is CancellationException)
        gate.callbackCompleted()
        assertFalse(applied)
        assertTrue(runCatching { gate.atPlaybackBoundary { applied = true } }.exceptionOrNull() is IllegalStateException)
        assertFalse(applied)
    }

    @Test fun aNeverStartedPlaybackDeviceDoesNotDelayMute() = runBlocking {
        val gate = NativeOutputMute()
        var applied = false
        val hold = async(start = CoroutineStart.UNDISPATCHED) {
            gate.atPlaybackBoundary { applied = true }
        }
        assertTrue("No playback write is in flight before startup", applied)
        assertTrue(hold.isCompleted)
        hold.await()
    }

    @Test fun anAlreadyStoppedPlaybackDeviceDoesNotDelayMute() = runBlocking {
        val gate = NativeOutputMute()
        gate.playbackStarted(); gate.playbackStopped()
        var applied = false
        val hold = async(start = CoroutineStart.UNDISPATCHED) {
            gate.atPlaybackBoundary { applied = true }
        }
        assertTrue("No playback write is in flight after stop", applied)
        assertTrue(hold.isCompleted)
        hold.await()
    }

    @Test fun stoppingPlaybackAppliesAnAlreadyQueuedMuteBeforeAcknowledgingIt() = runBlocking {
        val gate = NativeOutputMute()
        gate.playbackStarted()
        var applied = false
        val hold = async(start = CoroutineStart.UNDISPATCHED) {
            gate.atPlaybackBoundary { applied = true }
        }
        assertFalse(applied)
        gate.playbackStopped()
        hold.await()
        assertTrue(applied)
    }
}
