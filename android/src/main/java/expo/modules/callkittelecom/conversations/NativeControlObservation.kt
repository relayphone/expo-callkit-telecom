package expo.modules.callkittelecom.conversations

internal enum class NativeControlKind { HOLD, MUTE }
internal enum class NativeControlPoint { REQUESTED, PLATFORM_ACKNOWLEDGED, COMPLETED }
internal data class NativeControlObservation(val conversationId: String, val kind: NativeControlKind,
    val closed: Boolean, val point: NativeControlPoint, val atNs: Long,
    val origin: NativeActionOrigin = NativeActionOrigin.APP)
internal fun interface NativeControlObserver { fun observed(value: NativeControlObservation) }
