package expo.modules.callkittelecom.conversations

import android.telecom.DisconnectCause
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeOutgoingConnectionTest {
    @Test fun dialingAudioAndRoomRecoveryCannotActivateAnOutgoingCall() = runBlocking {
        val connection = NativeConnectionStatus(NativeCallDirection.OUTGOING)
        var telecomState = "DIALING"
        var activations = 0
        assertFalse(connection.connectsOnMedia)
        assertFalse(connection.canUseAudio(admitted = false, active = false, held = false))
        assertTrue(connection.canUseAudio(admitted = true, active = false, held = false))
        assertFalse(connection.canUseAudio(admitted = true, active = false, held = true))
        connection.observe(NativeMediaConnection.RECONNECTING)
        assertEquals("connecting", connection.connected(false))
        connection.observe(NativeMediaConnection.RECONNECTED)
        assertEquals("connecting", connection.connected(false))
        assertEquals("DIALING", telecomState)
        assertEquals(0, activations)
        connection.reportConnected { activations++; telecomState = "ACTIVE" }
        assertEquals("ACTIVE", telecomState)
        assertEquals(1, activations)
        assertEquals("connected", connection.connected(false))
        connection.observe(NativeMediaConnection.RECONNECTING)
        assertEquals("reconnecting", connection.connected(false))
        assertEquals("held", connection.connected(true))
    }

    @Test fun refusedActivationDoesNotCommitTheConnectedReport() = runBlocking {
        val connection = NativeConnectionStatus(NativeCallDirection.OUTGOING)
        assertTrue(runCatching { connection.reportConnected { error("Core refused") } }.isFailure)
        assertEquals("connecting", connection.connected(false))
        connection.reportConnected { }
        assertEquals("connected", connection.connected(false))
    }

    @Test fun incomingAudioStillRequiresAnswerAndMediaStillConnectsIt() {
        val connection = NativeConnectionStatus(NativeCallDirection.INCOMING)
        assertTrue(connection.connectsOnMedia)
        assertFalse(connection.canUseAudio(admitted = true, active = false, held = false))
        assertTrue(connection.canUseAudio(admitted = true, active = true, held = false))
        assertFalse(connection.canUseAudio(admitted = true, active = true, held = true))
        assertEquals("connected", connection.connected(false))
    }

    @Test fun unansweredIncomingCallIsMissed() {
        assertEquals(DisconnectCause.MISSED, unansweredDisconnectCode(NativeCallDirection.INCOMING))
    }

    @Test fun unansweredOutgoingCallEndedRemotely() {
        assertEquals(DisconnectCause.REMOTE, unansweredDisconnectCode(NativeCallDirection.OUTGOING))
    }
}
