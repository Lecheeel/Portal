package com.system.location.service.core.runtime

import com.system.location.service.core.location.LocationSample

/**
 * Per-scenario sample hand-off (P5-02).
 *
 * Each scenario has an independent latest sample and insertion sequence; the map is bounded so a
 * noisy producer cannot grow the system_server heap. Lease checks mirror [SampleStore].
 */
class ScenarioSampleStore(private val maxScenarios: Int = MAX_SCENARIOS) {
    private val entries = LinkedHashMap<String, StoredSample>()
    private var sequence = 0L

    init {
        require(maxScenarios in 1..MAX_SCENARIOS) { "maxScenarios out of range: $maxScenarios" }
    }

    @Synchronized
    fun publish(scenarioId: String, sample: LocationSample, elapsedRealtimeNanos: Long): Long {
        require(scenarioId.isNotBlank() && scenarioId.length <= MAX_ID_LENGTH) { "Invalid scenarioId" }
        require(elapsedRealtimeNanos >= 0L) { "elapsedRealtimeNanos must be non-negative" }
        sequence = Math.addExact(sequence, 1L)
        entries.remove(scenarioId)
        entries[scenarioId] = StoredSample(sequence, sample, elapsedRealtimeNanos)
        while (entries.size > maxScenarios) {
            entries.remove(entries.keys.first())
        }
        return sequence
    }

    @Synchronized
    fun latest(
        scenarioId: String,
        nowElapsedNanos: Long,
        maxAgeNanos: Long = SampleStore.DEFAULT_MAX_AGE_NANOS,
    ): StoredSample? {
        require(maxAgeNanos >= 0L) { "maxAgeNanos must be non-negative" }
        val entry = entries[scenarioId] ?: return null
        if (nowElapsedNanos < entry.storedAtElapsedNanos) return null
        return entry.takeIf { nowElapsedNanos - it.storedAtElapsedNanos < maxAgeNanos }
    }

    @Synchronized
    fun clear(scenarioId: String) {
        entries.remove(scenarioId)
    }

    @Synchronized
    fun scenarioIds(): Set<String> = entries.keys.toSet()

    /** One bounded, lease-checked view for a delivery tick; later publications cannot change it. */
    @Synchronized
    fun snapshot(nowElapsedNanos: Long, maxAgeNanos: Long = SampleStore.DEFAULT_MAX_AGE_NANOS): Map<String, LocationSample> {
        require(maxAgeNanos >= 0L) { "maxAgeNanos must be non-negative" }
        return entries.filterValues {
            nowElapsedNanos >= it.storedAtElapsedNanos && nowElapsedNanos - it.storedAtElapsedNanos < maxAgeNanos
        }.mapValues { it.value.sample }
    }

    /** Full authenticated restore. Preserve original sample deadlines, never receipt time. */
    @Synchronized
    fun replace(samples: Map<String, LocationSample>) {
        require(samples.size <= maxScenarios)
        require(samples.keys.all { it.isNotBlank() && it.length <= MAX_ID_LENGTH })
        var nextSequence = sequence
        val prepared = samples.mapValues { (_, sample) ->
            StoredSample(Math.addExact(nextSequence, 1L).also { nextSequence = it }, sample, sample.elapsedNanos)
        }
        entries.clear()
        entries.putAll(prepared)
        sequence = nextSequence
    }

    companion object {
        const val MAX_SCENARIOS = 64
        const val MAX_ID_LENGTH = 255
    }
}
