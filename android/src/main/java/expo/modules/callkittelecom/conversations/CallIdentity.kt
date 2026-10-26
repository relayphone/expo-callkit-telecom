package expo.modules.callkittelecom.conversations

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Shared SDK UUIDv5 identity. Validation of incoming invitations is separate. */
object CallIdentity {
    private val namespace = UUID.fromString("d460ee4a-efbc-5b0d-8035-0b915bf9551a")

    fun forServerCallId(serverCallId: String): UUID {
        val prefix = ByteBuffer.allocate(16)
            .putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
        val hash = MessageDigest.getInstance("SHA-1").apply {
            update(prefix)
            update(serverCallId.toByteArray(Charsets.UTF_8))
        }.digest()
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(hash)
        return UUID(bytes.long, bytes.long)
    }
}
