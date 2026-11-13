package expo.modules.callkittelecom.conversations

import io.livekit.android.AudioOptions
import org.junit.Assert.*
import org.junit.Test

class LiveKitNativeConfigurationTest {
    @Test fun defaultsKeepLiveKitNetworkMonitoringUnchanged() {
        assertFalse(NativeMediaOptions().disableNetworkMonitor)
        assertNull(LiveKitNativeConfiguration(NativeMediaOptions()).overrides().peerConnectionFactoryOptions)
    }

    @Test fun optedInSessionsUseTheSupportedPeerConnectionFactoryOverride() {
        val audio = AudioOptions(disableAudioPrewarming = true)
        val overrides = LiveKitNativeConfiguration(NativeMediaOptions(disableNetworkMonitor = true)).overrides(audio)
        assertTrue(overrides.peerConnectionFactoryOptions!!.disableNetworkMonitor)
        assertEquals(0, overrides.peerConnectionFactoryOptions!!.networkIgnoreMask)
        assertFalse(overrides.peerConnectionFactoryOptions!!.disableEncryption)
        assertSame(audio, overrides.audioOptions)
    }

    @Test fun configurationChangesApplyOnlyToNewSessions() {
        val previous = ConversationMedia.options
        try {
            ConversationMedia.configure(NativeMediaOptions())
            val original = LiveKitNativeConfiguration()
            ConversationMedia.configure(NativeMediaOptions(disableNetworkMonitor = true))
            val optedIn = LiveKitNativeConfiguration()
            assertNull(original.overrides().peerConnectionFactoryOptions)
            assertTrue(optedIn.overrides().peerConnectionFactoryOptions!!.disableNetworkMonitor)
            ConversationMedia.configure(NativeMediaOptions())
            assertTrue(optedIn.overrides().peerConnectionFactoryOptions!!.disableNetworkMonitor)
            assertNull(LiveKitNativeConfiguration().overrides().peerConnectionFactoryOptions)
        } finally { ConversationMedia.configure(previous) }
    }
}
