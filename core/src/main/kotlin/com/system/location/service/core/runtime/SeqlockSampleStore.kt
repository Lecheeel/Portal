package com.system.location.service.core.runtime

import com.system.location.service.core.location.LocationSample

/**
 * P5-06: JVM model of the double-buffered/seqlock sample exchange proposed in
 * `10-store-transport.md`. It is not shared memory itself; it validates the ordering rules a
 * future ashmem implementation must preserve: one writer, two slots, sequence retry on read.
 */
class SeqlockSampleStore : SampleStore {
    private val slots = arrayOfNulls<StoredSample>(2)

    @Volatile
    private var sequence = 0L

    @Volatile
    private var publishedIndex = -1

    @Synchronized
    override fun publish(sample: LocationSample, elapsedRealtimeNanos: Long): Long {
        require(elapsedRealtimeNanos >= 0L) { "elapsedRealtimeNanos must be non-negative" }
        val next = sequence + 1L
        val index = (next and 1L).toInt()
        slots[index] = StoredSample(next, sample, elapsedRealtimeNanos)
        publishedIndex = index
        sequence = next
        return next
    }

    override fun latest(nowElapsedNanos: Long, maxAgeNanos: Long): StoredSample? {
        require(maxAgeNanos >= 0L) { "maxAgeNanos must be non-negative" }
        repeat(MAX_RETRIES) {
            val before = sequence
            val index = publishedIndex
            if (index < 0) return null
            val entry = slots[index] ?: return null
            if (entry.sequence != before || sequence != before) return@repeat
            if (nowElapsedNanos < entry.storedAtElapsedNanos) return null
            return entry.takeIf { nowElapsedNanos - it.storedAtElapsedNanos <= maxAgeNanos }
        }
        return null
    }

    @Synchronized
    override fun clear() {
        slots[0] = null
        slots[1] = null
        publishedIndex = -1
        sequence = 0L
    }

    private companion object {
        const val MAX_RETRIES = 8
    }
}
