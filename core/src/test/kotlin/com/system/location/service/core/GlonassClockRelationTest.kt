package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GlonassClockRelationTest {
    private val date = LocalDate.of(2024, 1, 1)
    private val domain = GlonassTimeDomain("synthetic-clock-evidence", GlonassCalendarCoordinate(date.minusDays(1), 0.0),
        GlonassCalendarCoordinate(date.plusDays(1), 0.0))
    private val reference = domain.at(GlonassCalendarCoordinate(date, 0.0))!!
    private fun relation(tauN: Double = 123457 * Math.scalb(1.0, -30),
        gamma: Double = 347 * Math.scalb(1.0, -40), tauC: Double = -987654321 * Math.scalb(1.0, -31)) =
        GlonassClockRelation(reference, tauN, gamma, tauC, reference.plusSeconds(-800.0)!!, reference.plusSeconds(800.0)!!)

    @Test fun systemEpochBiasMatchesIndependentHighPrecisionAffineEquationForBothSigns() {
        val context = MathContext(50)
        for (signN in listOf(-1, 1)) for (signG in listOf(-1, 1)) for (signC in listOf(-1, 1)) {
            val r = relation(signN * 123457 * Math.scalb(1.0, -30),
                signG * 1023 * Math.scalb(1.0, -40), signC * 987654321 * Math.scalb(1.0, -31))
            val tn = BigDecimal(r.tauNSeconds)
            val gamma = BigDecimal(r.gammaN)
            val tc = BigDecimal(r.tauCSeconds)
            for (seconds in listOf(-800.0, -0.125, 0.0, 0.25, 800.0)) {
                // Solve (1-gamma)*(S-tb) = (U-tb)-tc-tn, then independently subtract U-tb.
                val system = BigDecimal(seconds)
                val satellite = system.subtract(tc).subtract(tn).divide(BigDecimal.ONE.subtract(gamma), context)
                val expected = satellite.subtract(system).toDouble()
                val result = r.biasAtSystemTime(reference.plusSeconds(seconds)!!)
                assertNotNull(result.reason, result.satelliteMinusSystemSeconds)
                assertEquals(expected, result.satelliteMinusSystemSeconds!!, 2e-16)
                val recovered = r.systemTimeFromSatelliteOffset(satellite.toDouble())
                assertNotNull(recovered.reason, recovered.systemTime)
                assertEquals(seconds, recovered.systemTime!!.secondsSince(reference)!!, 3e-13)
            }
        }
    }

    @Test fun affineInverseIsRequiredAndRelativisticTermsAreNotAddedAgain() {
        val r = relation(gamma = 1023 * Math.scalb(1.0, -40))
        val bias = r.biasAtSystemTime(reference).satelliteMinusSystemSeconds!!
        val approximation = -r.tauCSeconds - r.tauNSeconds
        assertTrue(kotlin.math.abs(bias - approximation) > 1e-10)
        val recovered = r.systemTimeFromSatelliteOffset(bias)
        assertEquals(0.0, recovered.systemTime!!.secondsSince(reference)!!, 1e-15)
        val zero = relation(0.0, 0.0, 0.0)
        assertEquals(0.0, zero.biasAtSystemTime(reference).satelliteMinusSystemSeconds!!, 0.0)
    }

    @Test fun rejectsChangedDomainsExpiredDatesAndUnknownOffsetsRatherThanWrapping() {
        val r = relation()
        val other = GlonassTimeDomain(domain.evidenceId, domain.from, domain.until)
        assertNull(r.biasAtSystemTime(other.at(reference.coordinate)!!).satelliteMinusSystemSeconds)
        assertNull(r.biasAtSystemTime(reference.plusSeconds(800.001)!!).satelliteMinusSystemSeconds)
        assertNull(r.biasAtSystemTime(reference.plusSeconds(-800.001)!!).satelliteMinusSystemSeconds)
        assertNull(r.biasAtSystemTime(reference.plusSeconds(-86_400.0)!!).satelliteMinusSystemSeconds)
        for (offset in listOf(-86_400.0, 800.0 - r.tauCSeconds - r.tauNSeconds + 1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(r.systemTimeFromSatelliteOffset(offset).systemTime)
        }
    }

    @Test fun acceptsOnlyFiniteBroadcastRangeCoefficientsAndOneOrderedApplicabilityDomain() {
        fun rejects(block: () -> Unit) {
            try { block(); fail("invalid GLONASS clock relation was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            rejects { relation(tauN = bad) }; rejects { relation(gamma = bad) }; rejects { relation(tauC = bad) }
        }
        rejects { relation(tauN = Math.scalb(1.0, -9)) }
        rejects { relation(gamma = Math.scalb(1.0, -30)) }
        rejects { relation(tauC = 1.0) }
        rejects { relation().copy(validFrom = reference.plusSeconds(1.0)!!) }
        rejects { relation().copy(validFrom = reference, validUntil = reference) }
        val other = GlonassTimeDomain(domain.evidenceId, domain.from, domain.until)
        rejects { relation().copy(validUntil = other.at(reference.plusSeconds(800.0)!!.coordinate)!!) }
    }
}
