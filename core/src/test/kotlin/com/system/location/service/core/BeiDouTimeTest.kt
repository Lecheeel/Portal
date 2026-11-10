package com.system.location.service.core

import com.system.location.service.core.gnss.BeiDouTime
import org.junit.Assert.*
import org.junit.Test

class BeiDouTimeTest {
    @Test fun fullBdtWeeksAreNormalizedWithoutAliasingGpsOrUtc() {
        val end = BeiDouTime(700, 604799.0)
        assertEquals(BeiDouTime(701, 1.0), end.plusSeconds(2.0))
        assertEquals(BeiDouTime(699, 604798.0), end.plusSeconds(-604801.0))
        assertEquals(604800.0, BeiDouTime(701, 604799.0).secondsSince(end), 0.0)
        assertEquals(BeiDouTime(700, 604799.0), end.plusSeconds(0.0))
        assertThrows(IllegalArgumentException::class.java) { BeiDouTime(0, 0.0).plusSeconds(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { BeiDouTime(0, 604800.0) }
        assertThrows(IllegalArgumentException::class.java) { BeiDouTime(-1, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { end.plusSeconds(Double.NaN) }
    }
}
