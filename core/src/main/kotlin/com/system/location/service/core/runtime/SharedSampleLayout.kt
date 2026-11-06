package com.system.location.service.core.runtime

import com.system.location.service.core.location.LocationSample

/**
 * P5-06: contiguous layout for one header and two fixed-size slots.
 *
 * Writer order matters: encode the inactive slot first, then publish the header. A future mmap
 * writer must add a release fence before the header store; readers add an acquire fence after the
 * header load.
 */
object SharedSampleLayout {
    const val SIZE = SharedSampleHeader.HEADER_BYTES + 2 * SharedSampleCodec.SLOT_BYTES

    fun write(
        buffer: ByteArray,
        sample: LocationSample,
        sequence: Long,
        elapsedNanos: Long,
    ): SharedSampleHeader {
        require(buffer.size == SIZE) { "invalid buffer size" }
        val index = (sequence and 1L).toInt()
        SharedSampleCodec.encode(sample).copyInto(buffer, SharedSampleHeader.HEADER_BYTES + index * SharedSampleCodec.SLOT_BYTES)
        val header = SharedSampleHeader(index, sequence, elapsedNanos)
        header.encode().copyInto(buffer, 0)
        return header
    }

    fun read(buffer: ByteArray): Pair<SharedSampleHeader, LocationSample>? {
        if (buffer.size != SIZE) return null
        return runCatching {
            val header = SharedSampleHeader.decode(buffer.copyOfRange(0, SharedSampleHeader.HEADER_BYTES))
            val offset = SharedSampleHeader.HEADER_BYTES + header.writeIndex * SharedSampleCodec.SLOT_BYTES
            val sample = SharedSampleCodec.decode(buffer.copyOfRange(offset, offset + SharedSampleCodec.SLOT_BYTES))
            header to sample
        }.getOrNull()
    }
}
