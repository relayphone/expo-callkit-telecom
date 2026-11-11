package expo.modules.callkittelecom.conversations

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.*
import org.junit.Test

class ConversationProximityTest {
    private class Lock : ProximityWakeLock {
        val events = mutableListOf<String>()
        override fun acquire() { events += "acquire" }
        override fun release() { events += "release" }
    }

    @Test fun acquiresOnlyForAnActiveEarpieceAndReleasesForEveryOtherRoute() {
        val lock = Lock()
        val proximity = ConversationProximity(lock)
        for (route in listOf(CallEndpointCompat.TYPE_SPEAKER, CallEndpointCompat.TYPE_BLUETOOTH,
            CallEndpointCompat.TYPE_WIRED_HEADSET, CallEndpointCompat.TYPE_UNKNOWN, null)) {
            proximity.update(false, CallEndpointCompat.TYPE_EARPIECE)
            proximity.update(true, route)
            assertTrue(lock.events.isEmpty())
            proximity.update(true, CallEndpointCompat.TYPE_EARPIECE)
            proximity.update(true, CallEndpointCompat.TYPE_EARPIECE)
            assertEquals(listOf("acquire"), lock.events)
            proximity.update(true, route)
            proximity.update(true, route)
            assertEquals(listOf("acquire", "release"), lock.events)
            lock.events.clear()
        }
    }

    @Test fun holdAndEndReleaseImmediatelyAndResumeCanReacquire() {
        val lock = Lock()
        val proximity = ConversationProximity(lock)
        proximity.update(true, CallEndpointCompat.TYPE_EARPIECE)
        proximity.update(false, CallEndpointCompat.TYPE_EARPIECE)
        proximity.update(true, CallEndpointCompat.TYPE_EARPIECE)
        proximity.update(false, null)
        proximity.update(false, null)
        assertEquals(listOf("acquire", "release", "acquire", "release"), lock.events)
    }

    @Test fun devicesWithoutProximitySupportNeedNoLock() {
        val proximity = ConversationProximity(null)
        proximity.update(true, CallEndpointCompat.TYPE_EARPIECE)
        proximity.update(false, null)
    }
}
