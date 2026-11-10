package com.system.location.service.core

import com.system.location.service.core.gnss.GalileoTime
import org.junit.Assert.*
import org.junit.Test

class GalileoTimeTest {
    @Test fun fullGstWeekCrossoversRetainAbsoluteAge() {
        val last = GalileoTime(1081, 604799.5)
        assertEquals(GalileoTime(1082, 0.5), last.plusSeconds(1.0))
        assertEquals(last, last.plusSeconds(1.0).plusSeconds(-1.0))
        assertEquals(604800.0, GalileoTime(1082, 1.0).secondsSince(GalileoTime(1081, 1.0)), 0.0)
        assertEquals(4096 * 604800.0, GalileoTime(5177, 1.0).secondsSince(GalileoTime(1081, 1.0)), 0.0)
    }
    @Test fun invalidOrTruncatedLookingTimesAreNotSilentlyNormalized() {
        assertThrows(IllegalArgumentException::class.java) { GalileoTime(-1, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GalileoTime(1, 604800.0) }
        assertThrows(IllegalArgumentException::class.java) { GalileoTime(1, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { GalileoTime(0, 0.0).plusSeconds(-1.0) }
    }
}
