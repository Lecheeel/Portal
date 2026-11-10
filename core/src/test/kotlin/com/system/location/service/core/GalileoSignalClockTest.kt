package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.math.BigDecimal
import java.math.MathContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoSignalClockTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-reference.json")!!
        .readText()).jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(r: JsonObject): GalileoBroadcastEphemeris {
        fun n(name: String) = r.n(name)
        val toe = GalileoTime(n("gstWeek").toInt(), n("toe"))
        return GalileoBroadcastEphemeris(n("svid").toInt(), toe, GalileoTime(toe.week, n("toc")),
            toe.plusSeconds(-7200.0), toe.plusSeconds(7200.0), n("iodnav").toInt(),
            GalileoClockReference.valueOf(r.getValue("clockReference").jsonPrimitive.content),
            n("sqrtA"), n("e"), n("i0"), n("omega0"), n("omega"), n("m0"), n("deltaN"), n("omegaDot"), n("iDot"),
            n("cuc"), n("cus"), n("crc"), n("crs"), n("cic"), n("cis"), n("af0"), n("af1"), n("af2"))
    }
    private val healthy = GalileoSignalStatus(0, 0)
    private fun inav(e: GalileoBroadcastEphemeris) = DecodedGalileoInav(e.copy(clockReference = GalileoClockReference.INAV_E1_E5B),
        e.toe, healthy, healthy, 107, Math.scalb(5.0, -32), Math.scalb(-7.0, -32))
    private fun fnav(e: GalileoBroadcastEphemeris) = DecodedGalileoFnav(e.copy(clockReference = GalileoClockReference.FNAV_E1_E5A),
        e.toe, healthy, 107, Math.scalb(5.0, -32))

    @Test fun realRecordClockVectorsMatchExactRationalFrequencyCorrections() {
        // Table 2 frequencies have exact ratios 154/115 and 154/118. Decimal rational
        // evaluation is independent of production's floating-point frequency division.
        fun expected(clock: Double, bgd: Double, numerator: Int, denominator: Int): Double =
            BigDecimal.valueOf(clock).subtract(BigDecimal.valueOf(bgd).multiply(BigDecimal(numerator))
                .divide(BigDecimal(denominator), MathContext.DECIMAL128)).toDouble()
        records.forEach { r ->
            val e = ephemeris(r)
            r.getValue("expected").jsonArray.map { it.jsonObject }.forEach { vector ->
                val time = e.toe.plusSeconds(vector.n("offsetSeconds"))
                val clock = vector.n("clockBiasSeconds") // independently computed RTKLIB C clock
                if (e.clockReference == GalileoClockReference.INAV_E1_E5B) {
                    val d = inav(e).copy(bgdE1E5aSeconds = r.n("bgdE1E5a"), bgdE1E5bSeconds = r.n("bgdE1E5b"))
                    assertEquals(expected(clock, d.bgdE1E5bSeconds, 1, 1),
                        GalileoSignalClock.correction(d, time, GalileoOpenServiceSignal.E1).correctionSeconds!!, 1e-16)
                    assertEquals(expected(clock, d.bgdE1E5bSeconds, 5929, 3481),
                        GalileoSignalClock.correction(d, time, GalileoOpenServiceSignal.E5B).correctionSeconds!!, 1e-16)
                } else {
                    val d = fnav(e).copy(bgdE1E5aSeconds = r.n("bgdE1E5a"))
                    assertEquals(expected(clock, d.bgdE1E5aSeconds, 23716, 13225),
                        GalileoSignalClock.correction(d, time, GalileoOpenServiceSignal.E5A).correctionSeconds!!, 1e-16)
                }
            }
        }
    }

    @Test fun unusedBgdCannotAffectTheSelectedClockReferenceAndZeroBgdPreservesClock() {
        val e = ephemeris(records.first())
        val a = inav(e)
        val first = GalileoSignalClock.correction(a, e.toe, GalileoOpenServiceSignal.E1)
        assertEquals(first, GalileoSignalClock.correction(a.copy(bgdE1E5aSeconds = -a.bgdE1E5aSeconds),
            e.toe, GalileoOpenServiceSignal.E1))
        val clock = GalileoBroadcastPropagator.propagate(a.ephemeris, e.toe).state!!.clockBiasSeconds
        assertEquals(clock, GalileoSignalClock.correction(a.copy(bgdE1E5bSeconds = 0.0), e.toe,
            GalileoOpenServiceSignal.E5B).correctionSeconds!!, 0.0)
        // Positive BGD subtracts; negative BGD adds. Comparing differences avoids a large af0.
        val positive = GalileoSignalClock.correction(a.copy(bgdE1E5bSeconds = Math.scalb(1.0, -32)), e.toe,
            GalileoOpenServiceSignal.E1).correctionSeconds!!
        val negative = GalileoSignalClock.correction(a.copy(bgdE1E5bSeconds = Math.scalb(-1.0, -32)), e.toe,
            GalileoOpenServiceSignal.E1).correctionSeconds!!
        assertEquals(Math.scalb(1.0, -31), negative - positive, 1e-18)
    }

    @Test fun signalHealthDataValidityAndMissingClockReferenceAreNotInterchangeable() {
        val e = ephemeris(records.first())
        val a = inav(e).copy(e1Status = GalileoSignalStatus(1, 0))
        assertNull(GalileoSignalClock.correction(a, e.toe, GalileoOpenServiceSignal.E1).correctionSeconds)
        assertNotNull(GalileoSignalClock.correction(a, e.toe, GalileoOpenServiceSignal.E5B).correctionSeconds)
        assertNull(GalileoSignalClock.correction(a, e.toe, GalileoOpenServiceSignal.E5A).correctionSeconds)
        val b = fnav(e)
        assertNull(GalileoSignalClock.correction(b, e.toe, GalileoOpenServiceSignal.E1).correctionSeconds)
        assertNull(GalileoSignalClock.correction(b, e.toe, GalileoOpenServiceSignal.E5B).correctionSeconds)
        assertNull(GalileoSignalClock.correction(b.copy(e5aStatus = GalileoSignalStatus(0, 1)), e.toe,
            GalileoOpenServiceSignal.E5A).correctionSeconds)
        assertNull(GalileoSignalClock.correction(a.copy(ephemeris = b.ephemeris), e.toe,
            GalileoOpenServiceSignal.E5B).correctionSeconds)
    }

    @Test fun spareSisaInvalidBgdAndStaleTimeCannotProduceACorrection() {
        val e = ephemeris(records.first())
        val a = inav(e)
        assertNull(GalileoSignalClock.correction(a.copy(sisaIndex = 255), e.toe, GalileoOpenServiceSignal.E1).correctionSeconds)
        for (bgd in listOf(Double.NaN, Double.POSITIVE_INFINITY, Math.scalb(512.0, -32), Math.scalb(-513.0, -32))) {
            assertNull(GalileoSignalClock.correction(a.copy(bgdE1E5bSeconds = bgd), e.toe,
                GalileoOpenServiceSignal.E1).correctionSeconds)
        }
        assertNull(GalileoSignalClock.correction(a, e.validUntil.plusSeconds(.001), GalileoOpenServiceSignal.E1).correctionSeconds)
    }
}
