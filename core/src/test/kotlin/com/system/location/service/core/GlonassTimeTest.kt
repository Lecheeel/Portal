package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GlonassTimeTest {
    private fun c(date: String, seconds: Double) = GlonassCalendarCoordinate(LocalDate.parse(date), seconds)
    private fun domain(id: String = "test-clock-only") = GlonassTimeDomain(id, c("2023-12-31", 0.0), c("2024-01-03", 0.0))

    @Test fun broadcastDateUsesCompleteFourYearIntervalAndGregorianBoundaries() {
        assertEquals(c("1996-01-01", 0.0), GlonassCalendarCoordinate.fromBroadcastDate(1, 1, 0.0))
        assertEquals(c("2002-04-18", 43200.0), GlonassCalendarCoordinate.fromBroadcastDate(2, 839, 43200.0))
        assertEquals(c("2027-12-31", 86399.0), GlonassCalendarCoordinate.fromBroadcastDate(8, 1461, 86399.0))
        assertEquals(c("2103-12-31", 0.0), GlonassCalendarCoordinate.fromBroadcastDate(27, 1460, 0.0))
        assertNull(GlonassCalendarCoordinate.fromBroadcastDate(27, 1461, 0.0))
        assertNull(GlonassCalendarCoordinate.fromBroadcastDate(0, 1, 0.0))
        assertNull(GlonassCalendarCoordinate.fromBroadcastDate(32, 1, 0.0))
        assertNull(GlonassCalendarCoordinate.fromBroadcastDate(8, 0, 0.0))
        assertNull(GlonassCalendarCoordinate.fromBroadcastDate(8, 1, 86400.0))
    }

    @Test fun continuousIntervalsCrossDaysYearsAndFourYearBoundariesWithoutLosingFractions() {
        val d = domain()
        val t = d.at(c("2023-12-31", 86399.875))!!
        val later = t.plusSeconds(0.25)!!
        assertEquals(c("2024-01-01", 0.125), later.coordinate)
        assertEquals(0.25, later.secondsSince(t)!!, 0.0)
        assertEquals(t.coordinate, later.plusSeconds(-0.25)!!.coordinate)
        assertEquals(86400.25, later.plusSeconds(86400.0)!!.secondsSince(t)!!, 0.0)
        assertNull(t.plusSeconds(Double.NaN))
        assertNull(t.plusSeconds(Double.MAX_VALUE))
        assertNull(t.plusSeconds(-86400.0))
        assertNull(d.at(d.until))
        assertNotNull(d.at(d.from))
    }

    @Test fun identicalLabelsAndDiagnosticIdsDoNotMergeIndependentCorrectionDomains() {
        val before = domain("same-descriptive-id")
        val after = domain("same-descriptive-id")
        val a = before.at(c("2024-01-01", 10800.0))!!
        val b = after.at(c("2024-01-01", 10800.0))!!
        assertNull(a.secondsSince(b))
        assertThrows(IllegalArgumentException::class.java) { GlonassTimeDomain("", before.from, before.until) }
        assertThrows(IllegalArgumentException::class.java) { GlonassTimeDomain("bad", before.until, before.from) }
    }

    @Test fun repeatedSubnanosecondArithmeticKeepsFractionsInsteadOfRoundingDaySizedDoubles() {
        val d = domain()
        val t = d.at(c("2024-01-01", 86399.0))!!
        val shifted = t.plusSeconds(0.083123456789123)!!
        assertEquals(0.083123456789123, shifted.secondsSince(t)!!, 1e-16)
        val restored = shifted.plusSeconds(-0.083123456789123)!!
        assertEquals(0.0, restored.secondsSince(t)!!, 2e-16)
        val picosecond = t.plusSeconds(1e-12)!!
        assertEquals(1e-12, picosecond.secondsSince(t)!!, 1e-20)
        assertEquals(1e-12, picosecond.plusSeconds(1.0)!!.secondsSince(t.plusSeconds(1.0)!!)!!, 1e-16)
        assertEquals(LocalDate.parse("2024-01-01"), t.plusSeconds(1.0 - 1e-12)!!.coordinate.date)
        assertNull(d.at(d.from)!!.plusSeconds(-1e-12))
        assertNotNull(d.at(d.until.copy(secondsOfDay = 0.0, date = d.until.date.minusDays(1)))!!.plusSeconds(86400.0 - 1e-9))
    }
}
