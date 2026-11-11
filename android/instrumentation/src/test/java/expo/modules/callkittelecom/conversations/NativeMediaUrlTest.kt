package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test

class NativeMediaUrlTest {
    @Test fun secureMediaWorksInDebugAndReleaseButCleartextRequiresDebug() {
        for (debuggable in listOf(false, true)) requireNativeMediaUrl("wss://media.example.test/room", debuggable)
        requireNativeMediaUrl("ws://10.0.2.2:7880", true)
        assertEquals("invalid_arguments", CommandRejection.from(
            runCatching { requireNativeMediaUrl("ws://media.example.test", false) }.exceptionOrNull() as Exception).code)
    }

    @Test fun malformedAddressesAndCredentialsAreRejectedInBothBuildModes() {
        for (debuggable in listOf(false, true)) for (url in listOf(
            "https://media.example.test", "file:///room", "wss:///room", "ws:///room",
            "wss://user:password@media.example.test", "ws://user@media.example.test", "wss://bad host",
        )) assertTrue(runCatching { requireNativeMediaUrl(url, debuggable) }.isFailure)
    }
}
