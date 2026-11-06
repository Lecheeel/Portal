package com.system.location.service.core.runtime

import com.system.location.service.core.config.ConfigEnvelope
import com.system.location.service.core.location.LocationSample

data class StoredSample(
    val sequence: Long,
    val sample: LocationSample,
    val storedAtElapsedNanos: Long,
)

/**
 * Versioned sample hand-off abstraction (P5-06).
 *
 * Hooks read `latest()` on a callback path; a store must be O(1) and must not touch disk. The lease
 * check is explicit so a stale sample can never be published after the controller stopped.
 */
interface SampleStore {
    fun publish(sample: LocationSample, elapsedRealtimeNanos: Long): Long

    fun latest(nowElapsedNanos: Long, maxAgeNanos: Long = DEFAULT_MAX_AGE_NANOS): StoredSample?

    fun clear()

    companion object {
        const val DEFAULT_MAX_AGE_NANOS = 5_000_000_000L
    }
}

class InMemorySampleStore : SampleStore {
    private var sequence = 0L
    private var latest: StoredSample? = null

    @Synchronized
    override fun publish(sample: LocationSample, elapsedRealtimeNanos: Long): Long {
        require(elapsedRealtimeNanos >= 0L) { "elapsedRealtimeNanos must be non-negative" }
        sequence += 1L
        latest = StoredSample(sequence, sample, elapsedRealtimeNanos)
        return sequence
    }

    @Synchronized
    override fun latest(nowElapsedNanos: Long, maxAgeNanos: Long): StoredSample? {
        require(maxAgeNanos >= 0L) { "maxAgeNanos must be non-negative" }
        val entry = latest ?: return null
        if (nowElapsedNanos < entry.storedAtElapsedNanos) return null
        return entry.takeIf { nowElapsedNanos - it.storedAtElapsedNanos <= maxAgeNanos }
    }

    @Synchronized
    override fun clear() {
        latest = null
    }
}

/** Versioned config hand-off abstraction; persistence/Binder implementations land in P5-06 later. */
interface ConfigStore {
    fun load(): ConfigEnvelope?
    fun save(config: ConfigEnvelope)
    fun clear()
}

class InMemoryConfigStore(initial: ConfigEnvelope? = null) : ConfigStore {
    private var config: ConfigEnvelope? = initial

    @Synchronized
    override fun load(): ConfigEnvelope? = config

    @Synchronized
    override fun save(config: ConfigEnvelope) {
        this.config = config
    }

    @Synchronized
    override fun clear() {
        config = null
    }
}
