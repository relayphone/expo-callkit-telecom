package expo.modules.callkittelecom.conversations

import androidx.core.telecom.CallAttributesCompat
import org.junit.Assert.*
import org.junit.Test

class ConversationCapabilitiesTest {
    @Test fun tonesAreOptionalWithoutChangingCoreHoldSupport() {
        assertEquals(false, ConversationCapabilities().snapshot()["tones"])
        assertEquals("unsupported", CommandRejection.from(
            runCatching { ConversationCapabilities().requireTones() }.exceptionOrNull() as Exception).code)
        val enabled = ConversationCapabilities(supportsTones = true)
        assertEquals(true, enabled.snapshot()["tones"])
        assertEquals(CallAttributesCompat.SUPPORTS_SET_INACTIVE, enabled.telecom)
        enabled.requireTones()
    }

    @Test fun defaultsKeepHoldSupportInCoreAndSnapshots() {
        val capabilities = ConversationCapabilities()
        assertEquals(CallAttributesCompat.SUPPORTS_SET_INACTIVE, capabilities.telecom)
        assertEquals(true, capabilities.snapshot()["hold"])
        capabilities.requireHold()
    }

    @Test fun disabledHoldIsAbsentFromCoreAndSnapshotsAndUsesTheTypedRejection() {
        val capabilities = ConversationCapabilities(supportsHold = false)
        assertEquals(0, capabilities.telecom and CallAttributesCompat.SUPPORTS_SET_INACTIVE)
        assertEquals(false, capabilities.snapshot()["hold"])
        val error = runCatching { capabilities.requireHold() }.exceptionOrNull() as Exception
        assertEquals("unsupported", CommandRejection.from(error).code)
    }
}
