package com.system.location.service.core

import com.system.location.service.core.gnss.GpsTime
import org.junit.Assert.*
import org.junit.Test

class GpsTimeTest {
    @Test fun fullWeekBoundaryPreservesTheActualElapsedInterval() {
        val previous = GpsTime(2000, 604799.75)
        val next = previous.plusSeconds(0.5)
        assertEquals(GpsTime(2001, 0.25), next)
        assertEquals(0.5, next.secondsSince(previous), 0.0)
        assertEquals(previous, next.plusSeconds(-0.5))
        // Equal time-of-week one week later is old evidence, not a zero-age orbit.
        assertEquals(604800.0, GpsTime(2001, 42.0).secondsSince(GpsTime(2000, 42.0)), 0.0)
    }

    @Test fun rejectsNonFiniteAmbiguousAndOutOfDomainTime() {
        for (seconds in listOf(-1.0, 604800.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { GpsTime(2000, seconds) }
        }
        assertThrows(IllegalArgumentException::class.java) { GpsTime(-1, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GpsTime(0, 0.0).plusSeconds(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { GpsTime(2000, 0.0).plusSeconds(Double.POSITIVE_INFINITY) }
    }
}
