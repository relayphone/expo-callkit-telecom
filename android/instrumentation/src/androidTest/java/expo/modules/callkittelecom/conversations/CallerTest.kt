package expo.modules.callkittelecom.conversations

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallerTest {
    @Test fun sdkPhoneVectorsNormalizeOnlyTheSpecifiedAsciiSeparators() {
        val caller = parse("phone", "+1 (202) 555-0123", " Cafe caller ", " host-42 ")
        assertEquals(NativeCaller.HandleType.PHONE, caller.handleType)
        assertEquals("+12025550123", caller.value)
        assertEquals("Cafe caller", caller.displayName)
        assertEquals("host-42", caller.hostContactID)
        assertEquals(mapOf("handleType" to "phone", "value" to "+12025550123",
            "displayName" to "Cafe caller", "hostContactID" to "host-42"), caller.asPayload())
        assertEquals(mapOf("id" to "host-42", "displayName" to "Cafe caller",
            "phoneNumber" to "+12025550123"), caller.asParticipant())
        assertEquals("+442079460958", parse("phone", "+44.20 (7946)-0958").value)
        assertEquals("+12", parse("phone", "+1-2").value)
        assertEquals("+123456789012345", parse("phone", "+123456789012345").value)
        for (value in listOf("2025550123", "+0123", "+1234567890123456", "+1-202-555-0123x5",
            "+١٢٠٢٥٥٥٠١٢٣", "+", "+1", "++12", "+12/34", "+12_34", "+1\u00A02", "+1\t2")) {
            assertNull("Invalid phone accepted: $value", NativeCaller.parse(payload("phone", value)))
        }
    }

    @Test fun sdkEmailVectorsPreserveCaseAndDoNotRequireADottedDomain() {
        val email = parse("email", " Alice@Example.COM ", "Alice")
        assertEquals("Alice@Example.COM", email.value)
        assertEquals(mapOf("id" to "Alice@Example.COM", "displayName" to "Alice",
            "email" to "Alice@Example.COM"), email.asParticipant())
        assertEquals("User@localhost", parse("email", "User@localhost").value)
        assertEquals("é@東京", parse("email", "é@東京").value)
        for (value in listOf("no-at", "@example.com", "a@", "a@@example.com", "a b@example.com",
            "a@b c", "a\u00A0b@example.com", "a\u2028b@example.com", "a\u200Bb@example.com")) {
            assertNull("Invalid email accepted: $value", NativeCaller.parse(payload("email", value)))
        }
    }

    @Test fun emailSeparatorMatchesSdkStandaloneGraphemeComparison() {
        // These outcomes were reproduced with the pinned SDK's Swift String.split predicate.
        for (value in listOf("a@b@\u0301c", "a@b@\uFE0Fc", "a\u0D4E@b@c",
            "a@\u0301b@c", "a@@\u0301b", "@\u0301a@b")) {
            val caller = parse("email", value)
            assertEquals(value, caller.value)
            assertEquals(value, caller.asParticipant()["email"])
        }
        for (value in listOf("a@\u0301b", "a@\uFE0Fb", "a\u0D4E@b", "a@\u0301")) {
            assertNull("An attached @ grapheme is not the SDK's email separator: $value",
                NativeCaller.parse(payload("email", value)))
        }
    }

    @Test fun genericIdentityAndHostContactIdStayOpaqueAndAreNeverInferred() {
        for (value in listOf("opaque-caller", "+12025550123", "Alice@example.com", "room / account:東京", "cafe\u0301")) {
            val caller = parse("generic", " $value ", " Opaque ")
            assertEquals(value, caller.value)
            assertEquals(mapOf("id" to value, "displayName" to "Opaque"), caller.asParticipant())
            assertEquals(mapOf("handleType" to "generic", "value" to value,
                "displayName" to "Opaque"), caller.asPayload())
        }
        val caller = parse("generic", "opaque-caller", "Caller", " app-contact / 42@example.com ")
        assertEquals("app-contact / 42@example.com", caller.asParticipant()["id"])
        assertEquals("opaque-caller", caller.value)
        assertEquals("opaque-caller", caller.address().schemeSpecificPart)
        assertEquals(caller.asPayload(), NativeCaller.parse(caller.asPayload())!!.asPayload())
        for (value in listOf("", " ", "a\nb", "a\u0000b")) {
            assertNull(NativeCaller.parse(payload("generic", value)))
        }
    }

    @Test fun foundationWhitespaceIsTrimmedBeforeTextValidation() {
        // Foundation also treats U+200B as edge whitespace; it remains a forbidden Cf internally.
        val padding = "\t\n\u000B\u000C\r \u0085\u00A0\u1680\u2000\u2001\u2002\u2003" +
            "\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u200B\u2028\u2029\u202F\u205F\u3000"
        val caller = parse("generic", padding + "opaque" + padding,
            padding + "Caller" + padding, padding + "host-42" + padding)
        assertEquals("opaque", caller.value)
        assertEquals("Caller", caller.displayName)
        assertEquals("host-42", caller.hostContactID)
        assertEquals("+12025550123", parse("phone", padding + "+1 (202) 555-0123" + padding).value)
        assertEquals("Alice@example.com", parse("email", padding + "Alice@example.com" + padding).value)
        // Generic text has no email-style ban on interior separator-category characters.
        assertEquals("a\u2028b", parse("generic", "a\u2028b").value)
        assertEquals("a\u00A0b", parse("generic", "a\u00A0b").value)
        for (value in listOf("\u001Copaque", "opaque\uFEFF")) {
            assertNull("A control character outside Foundation whitespace was trimmed", NativeCaller.parse(payload("generic", value)))
        }
    }

    @Test fun controlsAreRejectedByUnicodeCodePointInEveryTextField() {
        val controls = listOf("\u0000", "\u0085", "\u00AD", "\u200B", "\u2060", "\uFEFF",
            String(Character.toChars(0xE0001)))
        for (control in controls) {
            val text = "a${control}b"
            assertNull(NativeCaller.parse(payload("generic", text)))
            assertNull(NativeCaller.parse(payload("generic", "opaque", text)))
            assertNull(NativeCaller.parse(payload("generic", "opaque", "Caller", text)))
        }
    }

    @Test fun typedWireFieldsAreRequiredAndAbsentOptionalFieldsAreNotFabricated() {
        val valid = payload("generic", "opaque")
        val invalid = listOf(valid - "handleType", valid - "value", valid - "displayName",
            valid + ("handleType" to "unknown"), valid + ("handleType" to "Phone"),
            valid + ("value" to 42), valid + ("displayName" to false), valid + ("displayName" to " "),
            valid + ("hostContactID" to 42), valid + ("hostContactID" to " "),
            mapOf("id" to "legacy-id", "displayName" to "Legacy", "phoneNumber" to "+12025550123"))
        invalid.forEach { assertNull("Invalid typed Caller accepted: $it", NativeCaller.parse(it)) }
        val caller = NativeCaller.parse(valid + mapOf("hostContactID" to null,
            "id" to "ignored-legacy-id", "avatarUrl" to "ignored"))!!
        assertNull(caller.hostContactID)
        assertEquals(valid, caller.asPayload())
        assertEquals(mapOf("id" to "opaque", "displayName" to "Caller"), caller.asParticipant())
    }

    @Test fun communicationUrisUseTypedNormalizedValuesAndCoreSipCompatibilityIsLimitedTo26And27() {
        val phone = parse("phone", "+1 (202) 555-0123", "Caller", "host-42")
        val email = parse("email", " Alice@Example.COM ")
        val generic = parse("generic", "room:Account/42?x=1#part")
        val examples = listOf(
            Triple(phone, "tel:%2B12025550123", "sip:%2B12025550123"),
            Triple(email, "mailto:Alice%40Example.COM", "sip:Alice%40Example.COM"),
            Triple(generic, "sip:room%3AAccount%2F42%3Fx%3D1%23part", "sip:room%3AAccount%2F42%3Fx%3D1%23part"),
        )
        for ((caller, typedUri, legacyCoreUri) in examples) {
            val address = caller.address()
            assertEquals(typedUri, address.toString())
            assertEquals(caller.value, address.schemeSpecificPart)
            assertTrue(address.isOpaque)
            assertNull(address.query)
            assertNull(address.fragment)
            assertEquals(legacyCoreUri, caller.coreAddress(26).toString())
            assertEquals(legacyCoreUri, caller.coreAddress(27).toString())
            for (sdk in listOf(28, 29, 34, 35)) assertEquals(typedUri, caller.coreAddress(sdk).toString())
            assertEquals(caller.coreAddress(Build.VERSION.SDK_INT), caller.coreAddress())
        }
        assertEquals("tel", phone.coreAddress(35).scheme)
        assertEquals("+12025550123", phone.coreAddress(35).schemeSpecificPart)
    }

    private fun parse(type: String, value: String, name: String = "Caller", hostContactID: String? = null): NativeCaller =
        NativeCaller.parse(payload(type, value, name, hostContactID)) ?: error("Valid Caller rejected")

    private fun payload(type: String, value: String, name: String = "Caller", hostContactID: String? = null): Map<String, Any?> =
        buildMap {
            put("handleType", type); put("value", value); put("displayName", name)
            if (hostContactID != null) put("hostContactID", hostContactID)
        }
}
