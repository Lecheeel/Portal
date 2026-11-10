package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import org.junit.Assert.*
import org.junit.Test

class GalileoGpsTimeOffsetTest {
    private val mc = MathContext.DECIMAL128
    private val weekSeconds = BigDecimal(604800)
    private val origin = weekSeconds.multiply(BigDecimal(1024))
    private fun model(a0: Int, a1: Int, week: Int = 1081, hour: Int = 0) = GalileoGpsTimeOffset(
        week, hour, a0, a1, GalileoTime(0, 0.0), GalileoTime(Int.MAX_VALUE, 604799.999))
    private fun absolute(week: Int, tow: Double) = weekSeconds.multiply(BigDecimal(week)).add(BigDecimal(tow))
    private fun split(seconds: BigDecimal): Pair<Int, Double> {
        val week = seconds.divide(weekSeconds, 0, RoundingMode.FLOOR).intValueExact()
        return week to seconds.subtract(weekSeconds.multiply(BigDecimal(week))).toDouble()
    }
    private fun coefficients(m: GalileoGpsTimeOffset) =
        BigDecimal(m.a0Units).divide(BigDecimal(2).pow(35)) to BigDecimal(m.a1Units).divide(BigDecimal(2).pow(51))

    /** Evaluate the absolute-coordinate affine equation in Decimal128, not the production delta algorithm. */
    private fun expectedForward(m: GalileoGpsTimeOffset, gps: GpsTime): GalileoTime {
        val (a0, a1) = coefficients(m)
        val reference = absolute(m.referenceWeek, m.referenceHour * 3600.0)
        val alignedGps = absolute(gps.week, gps.secondsOfWeek).subtract(origin)
        val seconds = alignedGps.add(a0).subtract(a1.multiply(reference)).divide(BigDecimal.ONE.subtract(a1), mc)
        val (week, tow) = split(seconds)
        return GalileoTime(week, tow)
    }
    private fun expectedReverse(m: GalileoGpsTimeOffset, gst: GalileoTime): GpsTime {
        val (a0, a1) = coefficients(m)
        val reference = absolute(m.referenceWeek, m.referenceHour * 3600.0)
        val t = absolute(gst.week, gst.secondsOfWeek)
        val seconds = t.multiply(BigDecimal.ONE.subtract(a1)).subtract(a0).add(a1.multiply(reference)).add(origin)
        val (week, tow) = split(seconds)
        return GpsTime(week, tow)
    }

    @Test fun signedCoefficientExtremesAndReferenceHoursMatchDecimalAffineEquation() {
        for (a0 in listOf(-32768, -1, 0, 1, 32767)) for (a1 in listOf(-2048, -1, 0, 1, 2047))
            for (referenceWeek in listOf(1050, 1081, 1112)) for (hour in listOf(0, 167, 255))
                for (tow in listOf(1e-4, 12345.987654321, 604799.9999999)) {
                    val m = model(a0, a1, referenceWeek, hour)
                    val gps = GpsTime(2105, tow)
                    val expected = expectedForward(m, gps)
                    val actual = m.gpsToGalileo(gps).time!!
                    assertEquals("a0=$a0 a1=$a1 week=$referenceWeek hour=$hour tow=$tow", 0.0,
                        actual.secondsSince(expected), 1.2e-10)
                    val reverseExpected = expectedReverse(m, actual)
                    val reverse = m.galileoToGps(actual).time!!
                    assertEquals(0.0, reverse.secondsSince(reverseExpected), 1.2e-10)
                    assertEquals(0.0, reverse.secondsSince(gps), 2.4e-10)
                }
    }

    @Test fun implicitDenominatorIsNecessaryAndResolvedAtSmallSecondsOfWeek() {
        val m = model(32767, 2047, week = 1050)
        val gps = GpsTime(2105, 0.0)
        val expected = expectedForward(m, gps)
        val actual = m.gpsToGalileo(gps).time!!
        assertEquals(expected.week, actual.week)
        assertEquals(expected.secondsOfWeek, actual.secondsOfWeek, 1e-20)
        val shortcut = m.a0Seconds + m.a1SecondsPerSecond * (31 * 604800.0)
        assertTrue("evaluating the polynomial at GPS must not replace solving Eq. 24",
            kotlin.math.abs(actual.secondsOfWeek - shortcut) > 1e-18)
    }

    @Test fun nominalEpochChangeDoesNotEraseNonzeroGgtoOrSubNanosecondOffset() {
        val gps = GpsTime(2105, 0.0)
        val tiny = model(1, 0).gpsToGalileo(gps).time!!
        assertEquals(1081, tiny.week)
        assertEquals(Math.scalb(1.0, -35), tiny.secondsOfWeek, 0.0)
        val zero = model(0, 0).gpsToGalileo(gps).time!!
        assertEquals(GalileoTime(1081, 0.0), zero)
        assertEquals(gps, model(0, 0).galileoToGps(zero).time)
        // A 29ps negative offset cannot be represented at the end of the preceding
        // week with Double TOW; rounding to the boundary must not become an error.
        assertEquals(zero, model(-1, 0).gpsToGalileo(gps).time)
        assertEquals(gps, model(1, 0).galileoToGps(zero).time)
        assertNull(model(-1, 0).gpsToGalileo(GpsTime(1024, 0.0)).time)
    }

    @Test fun positiveAndNegativeOffsetsNormalizeAcrossWeekAndEpochBoundaries() {
        val positive = model(32767, 0, week = 0)
        val beforeNominalOrigin = GpsTime(1023, 604799.9999999)
        val firstGstWeek = positive.gpsToGalileo(beforeNominalOrigin).time!!
        assertEquals(0, firstGstWeek.week)
        assertEquals(0.0, firstGstWeek.secondsSince(expectedForward(positive, beforeNominalOrigin)), 1.2e-10)
        assertEquals(1023, positive.galileoToGps(GalileoTime(0, 0.0)).time!!.week)
        val negative = model(-32768, 0)
        val rollover = negative.gpsToGalileo(GpsTime(2105, 0.0)).time!!
        assertEquals(1080, rollover.week)
        assertTrue(rollover.secondsOfWeek > 604799.999)
        assertNull(negative.gpsToGalileo(GpsTime(1024, 0.0)).time)
    }

    @Test fun externalValidityIsCheckedInGstAndNotReplacedWithA31WeekExpiry() {
        val m = GalileoGpsTimeOffset(1081, 0, 0, 0, GalileoTime(1081, 100.0), GalileoTime(1081, 200.0))
        for (tow in listOf(100.0, 150.0, 200.0)) {
            assertNotNull(m.gpsToGalileo(GpsTime(2105, tow)).time)
            assertNotNull(m.galileoToGps(GalileoTime(1081, tow)).time)
        }
        for (tow in listOf(99.0, 201.0)) {
            assertNull(m.gpsToGalileo(GpsTime(2105, tow)).time)
            assertNull(m.galileoToGps(GalileoTime(1081, tow)).time)
        }
        assertNotNull(model(1, 1).gpsToGalileo(GpsTime(2105 + 32, 100.0)).time)
    }

    @Test fun supportedWeekOverflowAndUnrepresentableNegativeEpochAreExplicitFailures() {
        assertNull(model(0, 0).gpsToGalileo(GpsTime(0, 0.0)).time)
        assertNull(model(0, 0).galileoToGps(GalileoTime(Int.MAX_VALUE, 0.0)).time)
        val m = model(32767, 0, week = Int.MAX_VALUE - 1024)
        assertEquals(Int.MAX_VALUE - 1024,
            m.gpsToGalileo(GpsTime(Int.MAX_VALUE, 604799.999)).time!!.week)
    }

    @Test fun broadcastBoundsAndTheCombinedUnavailableSentinelAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { model(32768, 0) }
        assertThrows(IllegalArgumentException::class.java) { model(-32769, 0) }
        assertThrows(IllegalArgumentException::class.java) { model(0, 2048) }
        assertThrows(IllegalArgumentException::class.java) { model(0, -2049) }
        assertThrows(IllegalArgumentException::class.java) { model(0, 0, hour = 256) }
        assertThrows(IllegalArgumentException::class.java) { model(0, 0, week = -1) }
        assertThrows(IllegalArgumentException::class.java) { model(-1, -1, week = 63, hour = 255) }
        assertNotNull(model(-1, -1, week = 62, hour = 255)) // Individual all-one fields are legal.
        assertNotNull(model(-1, 0, week = 63, hour = 255))
    }
}
