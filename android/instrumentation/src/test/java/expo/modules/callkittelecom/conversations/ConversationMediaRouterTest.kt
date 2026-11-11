package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test

class ConversationMediaRouterTest {
    private val credentials = mapOf<String, Any?>("provider" to "livekit", "token" to "fixture", "expiresAt" to 123L)

    @Test fun outgoingAndRingingIncomingRouteToDifferentNativeLifetimes() {
        for ((direction, expected) in listOf(NativeCallDirection.OUTGOING to MediaProvisionRoute.OUTGOING,
            NativeCallDirection.INCOMING to MediaProvisionRoute.ANSWER)) {
            val supplied = mutableListOf<Pair<Map<String, Any?>, MediaProvisionRoute>>()
            val router = ConversationMediaRouter({ AndroidNativeMedia.LIVEKIT }, { id ->
                assertEquals("call", id)
                ConversationMediaRouter.Target(direction, true, true, false) { media, route -> supplied += media to route }
            }, { _, _ -> fail("Unexpected ignored provision") })
            val input = credentials.toMutableMap()
            router.provide("call", input)
            input["token"] = "changed"
            assertEquals(listOf(credentials to expected), supplied)
        }
    }

    @Test fun disabledUnknownTerminalOwnedAndNonRingingIncomingNeverReceiveCredentials() {
        val ignored = mutableListOf<MediaProvisionIgnored>()
        var selection = AndroidNativeMedia.NONE
        var target: ConversationMediaRouter.Target? = null
        val router = ConversationMediaRouter({ selection }, { target }, { id, reason -> assertEquals("call", id); ignored += reason })
        router.provide("call", credentials)
        selection = AndroidNativeMedia.LIVEKIT
        router.provide("call", credentials)
        for ((live, ringing, owned) in listOf(Triple(false, true, false), Triple(true, true, true), Triple(true, false, false))) {
            target = ConversationMediaRouter.Target(NativeCallDirection.INCOMING, live, ringing, owned) { _, _ -> fail("Ignored grant was delivered") }
            router.provide("call", credentials)
        }
        target = ConversationMediaRouter.Target(NativeCallDirection.OUTGOING, false, false, false) { _, _ -> fail("Terminal outgoing grant") }
        router.provide("call", credentials)
        assertEquals(listOf(MediaProvisionIgnored.DISABLED, MediaProvisionIgnored.UNKNOWN, MediaProvisionIgnored.TERMINAL,
            MediaProvisionIgnored.OWNED, MediaProvisionIgnored.NOT_RINGING, MediaProvisionIgnored.TERMINAL), ignored)
    }
}
