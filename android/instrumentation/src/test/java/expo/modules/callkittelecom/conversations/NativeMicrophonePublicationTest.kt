package expo.modules.callkittelecom.conversations

import io.livekit.android.room.participant.ParticipantPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class NativeMicrophonePublicationTest {
    private class LiveKitFake {
        val permissions = MutableStateFlow<ParticipantPermission?>(grant(false))
        var microphonePermissionChecks = 0
        var localTracks = 0
        val publications = mutableListOf<Boolean>()
        var reason: String? = null
        suspend fun answer() = publishNativeMicrophone(permissions, { reason = "PUBLISH_PERMISSION_NOT_GRANTED" }) {
            microphonePermissionChecks++
            localTracks++
            publications += true
        }
    }

    @Test fun receiveOnlyParticipantHasNoMicrophoneUntilTheAnswerGrant() = runBlocking {
        val participant = LiveKitFake()
        delay(20)
        assertEquals(0, participant.microphonePermissionChecks)
        assertEquals(0, participant.localTracks)
        assertTrue(participant.publications.isEmpty())
        participant.permissions.value = grant(true)
        withTimeout(1000) { participant.answer() }
        assertEquals(1, participant.microphonePermissionChecks)
        assertEquals(1, participant.localTracks)
        assertEquals(listOf(true), participant.publications)
        assertNull(participant.reason)
    }

    @Test fun delayedPermissionPublishesOnceWithinTheCallersDeadline() = runBlocking {
        val participant = LiveKitFake()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(1000) { participant.answer() } }
        try {
            assertTrue(participant.publications.isEmpty())
            assertEquals(0, participant.microphonePermissionChecks)
            participant.permissions.value = grant(true)
            answer.await()
            assertEquals(listOf(true), participant.publications)
            assertEquals(1, participant.localTracks)
        } finally { answer.cancelAndJoin() }
    }

    @Test fun missingPermissionFailsWithTheMediaReasonWithoutTouchingTheMicrophoneOrRetrying() = runBlocking {
        val participant = LiveKitFake()
        val failure = runCatching { withTimeout(20) { participant.answer() } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals("PUBLISH_PERMISSION_NOT_GRANTED", participant.reason)
        assertEquals(0, participant.microphonePermissionChecks)
        assertEquals(0, participant.localTracks)
        assertTrue(participant.publications.isEmpty())
        participant.permissions.value = grant(true)
        yield()
        assertTrue(participant.publications.isEmpty())
    }

    @Test fun endingThePermissionWaitDoesNotReportAMediaFailure() = runBlocking {
        val participant = LiveKitFake()
        val answer = launch(start = CoroutineStart.UNDISPATCHED) { participant.answer() }
        answer.cancelAndJoin()
        assertNull(participant.reason)
        assertEquals(0, participant.microphonePermissionChecks)
        assertTrue(participant.publications.isEmpty())
    }

    companion object {
        private fun grant(publish: Boolean) = ParticipantPermission(publish, true, false, false, false,
            emptyList(), false, false)
    }
}
