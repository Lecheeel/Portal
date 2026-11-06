package com.system.location.service.core.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * P5-06: fixed-size header for the shared-memory double buffer.
 *
 * Layout (little endian, 32 bytes):
 * magic i32 | version i16 | flags i16 | writeIndex i32 | sequence i64 | elapsedNanos i64 | crc32 i32
 *
 * The CRC covers the first 28 bytes. A reader must check the header and then re-check the sequence
 * after reading the slot; see [SeqlockSampleStore] for the object-level reference model.
 */
data class SharedSampleHeader(
    val writeIndex: Int,
    val sequence: Long,
    val elapsedNanos: Long,
) {
    init {
        require(writeIndex == 0 || writeIndex == 1) { "writeIndex must be 0 or 1" }
        require(sequence > 0L) { "sequence must be positive" }
        require(elapsedNanos >= 0L) { "elapsedNanos must be non-negative" }
    }

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(0)
        buffer.putInt(writeIndex)
        buffer.putLong(sequence)
        buffer.putLong(elapsedNanos)
        val bytes = buffer.array()
        val crc = CRC32().apply { update(bytes, 0, CRC_OFFSET) }.value.toInt()
        ByteBuffer.wrap(bytes, CRC_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
        return bytes
    }

    companion object {
        const val MAGIC = 0x504F5254 // "PORT"
        const val VERSION = 1
        const val HEADER_BYTES = 32
        private const val CRC_OFFSET = 28

        fun decode(bytes: ByteArray): SharedSampleHeader {
            require(bytes.size == HEADER_BYTES) { "invalid header size" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.int == MAGIC) { "invalid header magic" }
            require(buffer.short.toInt() == VERSION) { "unsupported header version" }
            buffer.short
            val writeIndex = buffer.int
            val sequence = buffer.long
            val elapsedNanos = buffer.long
            val expectedCrc = buffer.int
            val actualCrc = CRC32().apply { update(bytes, 0, CRC_OFFSET) }.value.toInt()
            require(expectedCrc == actualCrc) { "header crc mismatch" }
            return SharedSampleHeader(writeIndex, sequence, elapsedNanos)
        }
    }
}
