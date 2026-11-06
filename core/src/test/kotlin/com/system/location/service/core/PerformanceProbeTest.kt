package com.system.location.service.core

import com.system.location.service.core.performance.PerformanceProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PerformanceProbeTest {
    @Test fun emptyProbeHasNoSnapshot() {
        assertNull(PerformanceProbe().snapshot())
    }

    @Test fun percentilesUseSortedSamples() {
        val probe = PerformanceProbe()
        (1..100).forEach { probe.record(it.toLong()) }
        val snapshot = probe.snapshot()!!
        assertEquals(100, snapshot.samples)
        assertEquals(50L, snapshot.p50Nanos)
        assertEquals(95L, snapshot.p95Nanos)
        assertEquals(100L, snapshot.maxNanos)
        assertEquals(50L, snapshot.meanNanos)
    }

    @Test fun ringBufferKeepsMostRecentSamples() {
        val probe = PerformanceProbe(capacity = 3)
        listOf(100L, 200L, 300L, 1L).forEach { probe.record(it) }
        val snapshot = probe.snapshot()!!
        assertEquals(3, snapshot.samples)
        assertEquals(300L, snapshot.maxNanos)
        assertEquals(200L, snapshot.p50Nanos)
    }

    @Test fun clearResetsAndValidation() {
        val probe = PerformanceProbe()
        probe.record(10)
        probe.clear()
        assertNull(probe.snapshot())
        assertThrows(IllegalArgumentException::class.java) { probe.record(-1) }
        assertThrows(IllegalArgumentException::class.java) { PerformanceProbe(0) }
    }
}
