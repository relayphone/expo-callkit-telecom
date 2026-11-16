package expo.modules.callkittelecom.conversations

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeConnectFailureTest {
    @Test fun joinFailureIsClassifiedBeforeDisconnectionAndOnlyWhileConnecting() {
        val events = mutableListOf<Any>()
        val failure = NativeConnectFailure({ events += it }, { events += "disconnected" })
        assertTrue(failure.deferJoinFailure())
        assertTrue(events.isEmpty())
        failure.failed(NativePrewarmStage.CONNECT, IllegalStateException("private token=wss://private"))
        assertEquals(listOf(NativePrewarmFailure(NativePrewarmStage.CONNECT, "IllegalStateException"), "disconnected"), events)
        assertFalse(failure.deferJoinFailure())
        failure.failed(NativePrewarmStage.CONNECT, IllegalStateException("duplicate"))
        assertEquals(2, events.size)
        val connected = NativeConnectFailure({ fail("Unexpected diagnostic") }, { fail("Unexpected disconnect") })
        connected.connected()
        assertFalse(connected.deferJoinFailure())
    }

    @Test fun cancellationNeverTurnsRetainedJoinFailureIntoAnotherFailure() = runBlocking {
        val expired = checkNotNull(runCatching { withTimeout(20) { awaitCancellation() } }.exceptionOrNull())
        for (error in listOf(CancellationException("cancelled"), expired)) {
            val failure = NativeConnectFailure({ fail("Cancellation diagnostic") }, { fail("Cancellation disconnect") })
            assertTrue(failure.deferJoinFailure())
            failure.failed(NativePrewarmStage.CONNECT, error)
        }
    }
}
