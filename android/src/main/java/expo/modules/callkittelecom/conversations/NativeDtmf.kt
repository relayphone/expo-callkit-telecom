package expo.modules.callkittelecom.conversations

/** RFC 4733 telephone events carried by LiveKit's SIP DTMF packets. */
internal enum class NativeDtmfDigit(val code: Int, val digit: String) {
    ZERO(0, "0"), ONE(1, "1"), TWO(2, "2"), THREE(3, "3"), FOUR(4, "4"),
    FIVE(5, "5"), SIX(6, "6"), SEVEN(7, "7"), EIGHT(8, "8"), NINE(9, "9"),
    STAR(10, "*"), HASH(11, "#"), A(12, "A"), B(13, "B"), C(14, "C"), D(15, "D");

    companion object {
        fun parse(digits: String): List<NativeDtmfDigit> {
            require(digits.isNotEmpty()) { "invalid_arguments" }
            return digits.map { character ->
                entries.firstOrNull { it.digit.single() == character.uppercaseChar() }
                    ?: throw IllegalArgumentException("invalid_arguments")
            }
        }
    }
}
