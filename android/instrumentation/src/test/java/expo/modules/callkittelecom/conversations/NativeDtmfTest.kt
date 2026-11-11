package expo.modules.callkittelecom.conversations

import org.junit.Assert.*
import org.junit.Test

class NativeDtmfTest {
    @Test fun mapsAllTelephoneEventsAndNormalizesLowercaseLetters() {
        val tones = NativeDtmfDigit.parse("0123456789*#abcd")
        assertEquals((0..15).toList(), tones.map { it.code })
        assertEquals("0123456789*#ABCD", tones.joinToString("") { it.digit })
        assertEquals(NativeDtmfDigit.parse("ABCD"), NativeDtmfDigit.parse("abcd"))
    }

    @Test fun rejectsInvalidSequencesBeforeAnyPartialPublication() {
        for (digits in listOf("", "1x", "1,2", "1 2", "1\n", "１２", "☎")) {
            val error = runCatching { NativeDtmfDigit.parse(digits) }.exceptionOrNull() as Exception
            assertEquals("invalid_arguments", CommandRejection.from(error).code)
        }
    }
}
