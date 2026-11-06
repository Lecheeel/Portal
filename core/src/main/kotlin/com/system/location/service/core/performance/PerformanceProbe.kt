package com.system.location.service.core.performance

import kotlin.math.floor

data class PerformanceSnapshot(
    val samples: Int,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val maxNanos: Long,
    val meanNanos: Long,
)

/** Bounded latency probe for callback-path budgets (P7-04). */
class PerformanceProbe(private val capacity: Int = 256) {
    private val values = LongArray(capacity)
    private var count = 0
    private var next = 0

    init {
        require(capacity in 1..MAX_CAPACITY) { "capacity out of range" }
    }

    @Synchronized
    fun record(nanos: Long) {
        require(nanos >= 0L) { "nanos must be non-negative" }
        values[next] = nanos
        next = (next + 1) % capacity
        if (count < capacity) count += 1
    }

    @Synchronized
    fun snapshot(): PerformanceSnapshot? {
        if (count == 0) return null
        val sorted = values.copyOf(count).apply { sort() }
        val p50 = sorted[floor((count - 1) * 0.50).toInt()]
        val p95 = sorted[floor((count - 1) * 0.95).toInt()]
        val mean = sorted.sum() / count
        return PerformanceSnapshot(count, p50, p95, sorted.last(), mean)
    }

    @Synchronized
    fun clear() {
        count = 0
        next = 0
    }

    companion object {
        const val MAX_CAPACITY = 4_096
    }
}
