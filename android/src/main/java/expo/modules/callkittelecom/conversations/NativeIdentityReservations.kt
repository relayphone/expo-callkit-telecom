package expo.modules.callkittelecom.conversations

internal enum class NativeCallDirection { INCOMING, OUTGOING }

/** Main-confined admission ownership; validation never trims or rewrites the application's ID. */
internal class NativeIdentityReservations {
    class Reservation internal constructor(val id: String, val direction: NativeCallDirection)
    private val current = mutableMapOf<String, Reservation>()

    fun reserve(id: String, direction: NativeCallDirection): Reservation {
        if (!NativeCallEnded.validIdentifier(id)) throw ConversationFailure("invalid_arguments")
        current[id]?.let {
            if (direction == NativeCallDirection.INCOMING && it.direction == direction) return it
            throw ConversationFailure("invalid_arguments")
        }
        return Reservation(id, direction).also { current[id] = it }
    }

    fun isCurrent(reservation: Reservation): Boolean = current[reservation.id] === reservation

    /** Late cleanup from an older admission cannot release a newer call with the same raw ID. */
    fun release(reservation: Reservation): Boolean {
        if (!isCurrent(reservation)) return false
        current.remove(reservation.id)
        return true
    }
}
