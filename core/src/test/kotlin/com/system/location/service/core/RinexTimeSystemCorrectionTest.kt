package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class RinexTimeSystemCorrectionTest {
    private val example = "GAGP  9.0221874416E-10 0.000000000E+00 259200 2444          "

    @Test fun officialHeaderColumnsAndLegacyLabelKeepGalileoMinusGpsSign() {
        val parsed = RinexTimeSystemCorrection.parse(example)
        assertEquals("GAGP", parsed.type)
        assertEquals(9.0221874416e-10, parsed.a0Seconds, 0.0)
        assertEquals(0.0, parsed.a1SecondsPerSecond, 0.0)
        assertEquals(259200, parsed.referenceSeconds)
        assertEquals(2444, parsed.referenceWeek)
        assertEquals("", parsed.source)
        assertNull(parsed.utcIdentifier)
        assertEquals(parsed, RinexTimeSystemCorrection.parse(example.replace("GAGP", "GPGA")))
        // A5 example, same fixed columns with adjacent signed coefficients and source/U fields.
        val supplied = "GAGP -2.0081643015e-09-9.769962617e-15 432000 1849    E12 0 "
        val withSource = RinexTimeSystemCorrection.parse(supplied)
        assertEquals("E12", withSource.source)
        assertEquals(0, withSource.utcIdentifier)
        assertTrue(withSource.a0Seconds < 0 && withSource.a1SecondsPerSecond < 0)
    }

    @Test fun implicitEquationIsSolvedAndRoundTripsAcrossTheNativeWeekBoundary() {
        // Deliberately amplified valid synthetic slope to distinguish direct RHS evaluation.
        val correction = RinexTimeSystemCorrection("GAGP", 0.02, 5e-7, 604799, 2443, "test", null)
        val from = GalileoTime(1419, 604790.0)
        val until = GalileoTime(1420, 100.0)
        val gps = GpsTime(2443, 604799.99)
        val gst = correction.gpsToGalileo(gps, from, until)!!
        assertEquals(1420, gst.week)
        val delta = 0.99
        val expected = 0.99 + (0.02 + 5e-7 * delta) / (1 - 5e-7) - 1.0
        assertEquals(expected, gst.secondsOfWeek, 1e-10)
        assertEquals(0.0, correction.galileoToGps(gst, from, until)!!.secondsSince(gps), 1e-10)
        assertNull(correction.gpsToGalileo(gps.plusSeconds(1000.0), from, until))
        assertNull(correction.galileoToGps(until.plusSeconds(1.0), from, until))
    }

    @Test fun parserRejectsMalformedUnknownAndNonFiniteCorrections() {
        for (bad in listOf(example.replace("GAGP", "XXXX"), example.replace("9.0221874416E-10", "9.0221874416E999"),
            example.replace("259200", "604800"), example.take(45), example.replace("2444", "-444"))) {
            assertThrows(IllegalArgumentException::class.java) { RinexTimeSystemCorrection.parse(bad) }
        }
        val wrongSystem = RinexTimeSystemCorrection("BDUT", 0.0, 0.0, 14, 1088, "", null)
        assertThrows(IllegalArgumentException::class.java) {
            wrongSystem.gpsToGalileo(GpsTime(2444, 0.0), GalileoTime(1420, 0.0), GalileoTime(1420, 1.0))
        }
    }

    @Test fun agreesWithIndependentlyDecodedBroadcastCoefficientsWithoutQuantizingRinexAgain() {
        val from = GalileoTime(1420, 0.0)
        val until = GalileoTime(1420, 604799.0)
        val decoded = GalileoGpsTimeOffset(1420, 72, 31, -17, from, until)
        val rinex = RinexTimeSystemCorrection("GAGP", decoded.a0Seconds, decoded.a1SecondsPerSecond,
            259200, 2444, "E01", null)
        for (seconds in listOf(1.0, 100000.25, 259200.0, 600000.0)) {
            val gps = GpsTime(2444, seconds)
            val expected = decoded.gpsToGalileo(gps).time!!
            val actual = rinex.gpsToGalileo(gps, from, until)!!
            assertEquals(0.0, actual.secondsSince(expected), 1e-12)
            assertEquals(0.0, rinex.galileoToGps(actual, from, until)!!.secondsSince(gps), 1e-10)
        }
        val singular = rinex.copy(a1SecondsPerSecond = 1.0)
        assertNull(singular.gpsToGalileo(GpsTime(2444, 100.0), from, until))
        assertNull(singular.galileoToGps(GalileoTime(1420, 100.0), from, until))
    }
}
