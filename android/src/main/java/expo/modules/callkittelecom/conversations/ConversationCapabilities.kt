package expo.modules.callkittelecom.conversations

import androidx.core.telecom.CallAttributesCompat

/** Captured per call so advertised support and accepted controls always agree. */
internal data class ConversationCapabilities(val supportsHold: Boolean = true, val supportsTones: Boolean = false) {
    val telecom: Int get() = if (supportsHold) CallAttributesCompat.SUPPORTS_SET_INACTIVE else 0
    fun snapshot(): Map<String, Boolean> = mapOf("hold" to supportsHold, "merge" to false, "tones" to supportsTones, "video" to true)
    fun requireHold() { check(supportsHold) { "unsupported" } }
    fun requireTones() { check(supportsTones) { "unsupported" } }
}
