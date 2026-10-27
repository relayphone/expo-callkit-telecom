package expo.modules.callkittelecom.conversations

import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Build
import java.util.Locale

/** The host's typed calling identity, matching ios-voip-sdk's validated Caller. */
internal class NativeCaller private constructor(
    val handleType: HandleType,
    val value: String,
    val displayName: String,
    val hostContactID: String?,
) {
    enum class HandleType(val wireValue: String) { PHONE("phone"), EMAIL("email"), GENERIC("generic") }

    fun asPayload(): Map<String, Any?> = buildMap {
        put("handleType", handleType.wireValue)
        put("value", value)
        put("displayName", displayName)
        if (hostContactID != null) put("hostContactID", hostContactID)
    }

    fun asParticipant(): Map<String, Any?> = buildMap {
        put("id", hostContactID ?: value)
        put("displayName", displayName)
        when (handleType) {
            HandleType.PHONE -> put("phoneNumber", value)
            HandleType.EMAIL -> put("email", value)
            HandleType.GENERIC -> Unit
        }
    }

    /** Notification affinity and caller filtering use the declared communication type. */
    fun address(): Uri = Uri.fromParts(when (handleType) {
        HandleType.PHONE -> "tel"
        HandleType.EMAIL -> "mailto"
        HandleType.GENERIC -> "sip"
    }, value, null)

    /** Core-Telecom 1.0.1 documents the SIP address constraint only for API 26 and 27. */
    fun coreAddress(sdkInt: Int = Build.VERSION.SDK_INT): Uri =
        if (sdkInt == 26 || sdkInt == 27) Uri.fromParts("sip", value, null) else address()

    companion object {
        fun parse(raw: Map<*, *>): NativeCaller? {
            val type = HandleType.entries.firstOrNull { it.wireValue == raw["handleType"] } ?: return null
            val name = trimFoundation(raw["displayName"] as? String ?: return null)
            if (!validText(name)) return null
            var value = trimFoundation(raw["value"] as? String ?: return null)
            when (type) {
                HandleType.PHONE -> {
                    // A country code is mandatory; never infer a region or strip an extension.
                    value = value.filterNot { it in " ()-." }
                    if (!value.startsWith('+')) return null
                    val digits = value.drop(1)
                    if (digits.length !in 2..15 || digits[0] !in '1'..'9' || digits.any { it !in '0'..'9' }) return null
                }
                HandleType.EMAIL, HandleType.GENERIC -> {
                    if (!validText(value)) return null
                    if (type == HandleType.EMAIL) {
                        if (!validEmail(value)) return null
                    }
                }
            }
            val rawContact = raw["hostContactID"]
            if (rawContact != null && rawContact !is String) return null
            val contact = (rawContact as? String)?.let(::trimFoundation)
            if (contact != null && !validText(contact)) return null
            return NativeCaller(type, value, name, contact)
        }

        // Foundation controlCharacters comprises Unicode categories Cc and Cf, including
        // supplementary formatting characters. Char/ISO-control checks would miss them.
        private fun validText(value: String): Boolean = value.isNotEmpty() && !value.codePoints().anyMatch {
            Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt()
        }

        private fun validEmail(value: String): Boolean {
            if (value.codePoints().anyMatch(::foundationWhitespace)) return false
            // Swift splits String by Character: an @ attached to a combining mark or
            // a preceding grapheme-prepend character is not the separator Character.
            val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
            characters.setText(value)
            var start = characters.first()
            var separator = -1
            while (true) {
                val end = characters.next()
                if (end == BreakIterator.DONE) break
                if (end == start + 1 && value[start] == '@') {
                    if (separator != -1) return false
                    separator = start
                }
                start = end
            }
            return separator > 0 && separator < value.lastIndex
        }

        private fun trimFoundation(value: String): String = value.trim { foundationWhitespace(it.code) }

        // Exact Foundation whitespacesAndNewlines membership, including its U+200B edge trim.
        private fun foundationWhitespace(codePoint: Int): Boolean = when (codePoint) {
            in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
            in 0x2000..0x200B, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }
    }
}
