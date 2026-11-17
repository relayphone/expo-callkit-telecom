package expo.modules.callkittelecom.conversations

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** The portable Swift/TypeScript marker: 44 bytes, MSB-first in a centered 24 by 20 grid. */
internal data class SyntheticVideoMarker(val fixture: UUID, val direction: Int, val epoch: Long,
    val counter: Long, val sourceTimeUs: Long) {
    init {
        require(direction in 0..1 && epoch in 0..0xffffffffL && counter in 0..0xffffffffL &&
            sourceTimeUs in 0..9007199254740991L)
    }
    fun luma(width: Int = 160, height: Int = 120): ByteArray {
        geometry(width, height, width)
        val bytes = ByteBuffer.allocate(44).put(byteArrayOf(0x56, 0x4d, 1))
            .putLong(fixture.mostSignificantBits).putLong(fixture.leastSignificantBits).put(direction.toByte())
            .putInt(epoch.toInt()).putInt(counter.toInt()).putLong(sourceTimeUs).array()
        digest(bytes).copyInto(bytes, 36, 0, 8)
        val cell = minOf(width / 24, height / 20)
        val left = (width - cell * 24) / 2; val top = (height - cell * 20) / 2
        return ByteArray(width * height) { 128.toByte() }.also { pixels ->
            repeat(480) { i ->
                val bit = if (i < 352) (bytes[i / 8].toInt() ushr (7 - i % 8)) and 1 else i % 2
                repeat(cell) { y -> repeat(cell) { x ->
                    pixels[(top + i / 24 * cell + y) * width + left + i % 24 * cell + x] =
                        (if (bit == 0) 16 else 235).toByte()
                } }
            }
        }
    }
    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(36))
        private fun geometry(width: Int, height: Int, stride: Int) {
            require(width in 144..1920 && height in 120..1080 && stride in width..16384)
        }
        fun decode(y: ByteArray, width: Int, height: Int, stride: Int = width): SyntheticVideoMarker {
            geometry(width, height, stride); require(y.size.toLong() >= stride.toLong() * height)
            val bytes = ByteArray(44); val cell = minOf(width / 24, height / 20)
            val left = (width - cell * 24) / 2; val top = (height - cell * 20) / 2
            repeat(480) { i ->
                val x = left + i % 24 * cell + cell / 2; val row = top + i / 24 * cell + cell / 2
                var sum = 0
                for (dy in -1..1) for (dx in -1..1) sum += y[(row + dy) * stride + x + dx].toInt() and 255
                val value = sum / 9; require(value <= 64 || value >= 192)
                val bit = if (value >= 192) 1 else 0
                if (i < 352) bytes[i / 8] = (bytes[i / 8].toInt() or (bit shl (7 - i % 8))).toByte()
                else require(bit == i % 2)
            }
            require(bytes.take(3) == listOf<Byte>(0x56, 0x4d, 1) &&
                bytes.copyOfRange(36, 44).contentEquals(digest(bytes).copyOf(8)))
            val input = ByteBuffer.wrap(bytes).apply { position(3) }
            return SyntheticVideoMarker(UUID(input.long, input.long), input.get().toInt(),
                input.int.toLong() and 0xffffffffL, input.int.toLong() and 0xffffffffL, input.long)
        }
    }
}
