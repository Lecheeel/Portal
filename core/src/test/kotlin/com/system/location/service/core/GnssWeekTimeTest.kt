package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssWeekTimeTest {
    @Test fun tinyNegativeShiftAtBoundaryRoundsCanonicallyInsteadOfThrowing() {
        for (seconds in listOf(-1e-12, -Math.scalb(1.0, -35), -Double.MIN_VALUE)) {
            assertEquals(GpsTime(123, 0.0), GpsTime(123, 0.0).plusSeconds(seconds))
            assertEquals(GalileoTime(123, 0.0), GalileoTime(123, 0.0).plusSeconds(seconds))
        }
    }

    @Test fun representableSubNanosecondRolloverAndOrdinaryWeekShiftsKeepTheirSign() {
        for (seconds in listOf(-1e-9, -0.25, -604800.25)) {
            val gps = GpsTime(123, 0.0).plusSeconds(seconds)
            val gst = GalileoTime(123, 0.0).plusSeconds(seconds)
            assertTrue(gps.week < 123)
            assertEquals(gps.week, gst.week)
            assertEquals(gps.secondsOfWeek, gst.secondsOfWeek, 0.0)
            assertEquals(seconds, gps.secondsSince(GpsTime(123, 0.0)), 1.2e-10)
        }
        assertEquals(GpsTime(124, 0.25), GpsTime(123, 604799.75).plusSeconds(0.5))
        assertEquals(GalileoTime(124, 0.25), GalileoTime(123, 604799.75).plusSeconds(0.5))
        assertEquals(GpsTime(0, 0.0), GpsTime(1, 0.0).plusSeconds(-604800.0))
        assertEquals(GalileoTime(0, 0.0), GalileoTime(1, 0.0).plusSeconds(-604800.0))
    }

    @Test fun negativeEpochsAndOverflowAreRejectedBeforeRoundingOrIntegerConversion() {
        for (seconds in listOf(-1e-12, -Double.MIN_VALUE, -604800.0, -Double.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GpsTime(0, 0.0).plusSeconds(seconds) }
            assertThrows(IllegalArgumentException::class.java) { GalileoTime(0, 0.0).plusSeconds(seconds) }
        }
        for (seconds in listOf(604800.0, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { GpsTime(Int.MAX_VALUE, 0.0).plusSeconds(seconds) }
            assertThrows(IllegalArgumentException::class.java) { GalileoTime(Int.MAX_VALUE, 0.0).plusSeconds(seconds) }
        }
        assertEquals(GpsTime(Int.MAX_VALUE, 0.0), GpsTime(Int.MAX_VALUE, 0.0).plusSeconds(-1e-12))
        assertEquals(GalileoTime(Int.MAX_VALUE, 0.0), GalileoTime(Int.MAX_VALUE, 0.0).plusSeconds(-1e-12))
    }
}
