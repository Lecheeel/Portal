package com.system.location.service.core.runtime

import com.system.location.service.core.location.LocationSample

/**
 * P5-06: object-level model of the proposed header + two-slot shared buffer.
 *
 * The header is published as a volatile immutable value after the inactive slot is fully written;
 * readers copy the slot referenced by the header and re-check the header. A future mmap
 * implementation must provide the equivalent ordering (release/acquire or seqlock).
 */
class SharedSampleBuffer : SampleStore {
    private val slots = arrayOf(ByteArray(SharedSampleCodec.SLOT_BYTES), ByteArray(SharedSampleCodec.SLOT_BYTES))

    @Volatile
    private var publishedHeader: SharedSampleHeader? = null

    @Synchronized
    override fun publish(sample: LocationSample, elapsedRealtimeNanos: Long): Long {
        require(elapsedRealtimeNanos >= 0L) { "elapsedRealtimeNanos must be non-negative" }
        val sequence = (publishedHeader?.sequence ?: 0L) + 1L
        val index = (sequence and 1L).toInt()
        SharedSampleCodec.encode(sample).copyInto(slots[index])
        publishedHeader = SharedSampleHeader(index, sequence, elapsedRealtimeNanos)
        return sequence
    }

    override fun latest(nowElapsedNanos: Long, maxAgeNanos: Long): StoredSample? {
        require(maxAgeNanos >= 0L) { "maxAgeNanos must be non-negative" }
        repeat(MAX_RETRIES) {
            val header = publishedHeader ?: return null
            val bytes = slots[header.writeIndex].copyOf()
            if (publishedHeader != header) return@repeat
            val sample = runCatching { SharedSampleCodec.decode(bytes) }.getOrNull() ?: return null
            if (nowElapsedNanos < header.elapsedNanos) return null
            return StoredSample(header.sequence, sample, header.elapsedNanos)
                .takeIf { nowElapsedNanos - it.storedAtElapsedNanos <= maxAgeNanos }
        }
        return null
    }

    @Synchronized
    override fun clear() {
        publishedHeader = null
    }

    private companion object {
        const val MAX_RETRIES = 8
    }
}
