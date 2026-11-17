package expo.modules.callkittelecom

/** Resolves library references through the standalone host's merged Android resources. */
internal object R {
    object string {
        val expo_callkit_telecom_hang_up: Int
            get() = expo.modules.callkittelecom.instrumentation.R.string.expo_callkit_telecom_hang_up
    }
}
