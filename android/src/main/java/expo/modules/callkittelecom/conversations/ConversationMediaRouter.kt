package expo.modules.callkittelecom.conversations

internal enum class MediaProvisionRoute { OUTGOING, ANSWER }
internal enum class MediaProvisionIgnored(val reason: String) {
    DISABLED("nativeMediaDisabled"), UNKNOWN("unknownConversation"), TERMINAL("terminal"),
    OWNED("alreadyHasSession"), NOT_RINGING("notRinging")
}

/** Routing uses native ownership, never the presence of credentials in a public snapshot. */
internal class ConversationMediaRouter(
    private val selection: () -> AndroidNativeMedia,
    private val find: (String) -> Target?,
    private val ignored: (String, MediaProvisionIgnored) -> Unit,
) {
    class Target(val direction: NativeCallDirection, val live: Boolean, val ringing: Boolean,
                 val hasSession: Boolean, val supply: (Map<String, Any?>, MediaProvisionRoute) -> Unit)

    fun provide(id: String, media: Map<String, Any?>) {
        if (selection() != AndroidNativeMedia.LIVEKIT) { ignored(id, MediaProvisionIgnored.DISABLED); return }
        val target = find(id) ?: run { ignored(id, MediaProvisionIgnored.UNKNOWN); return }
        val reason = when {
            !target.live -> MediaProvisionIgnored.TERMINAL
            target.hasSession -> MediaProvisionIgnored.OWNED
            target.direction == NativeCallDirection.INCOMING && !target.ringing -> MediaProvisionIgnored.NOT_RINGING
            else -> null
        }
        if (reason != null) { ignored(id, reason); return }
        target.supply(media.toMap(), if (target.direction == NativeCallDirection.OUTGOING) MediaProvisionRoute.OUTGOING else MediaProvisionRoute.ANSWER)
    }
}
