package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RinexBeiDouTimeScaleTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val correction = RinexTimeSystemCorrection("BDUT", .25, 1e-5, 0, 1088, "", 7)
    private val epoch = Instant.parse("2026-11-07T23:59:56Z").toEpochMilli()

    @Test fun nonzeroPolynomialUsesBdtReferenceAndSolvesItsImplicitEquationAcrossWeekBoundary() {
        val scale = RinexBeiDouTimeScale(utc, correction, 604800.0)
        val at = epoch + 100000
        val time = scale.atUtcMillis(at)!!
        val delta = time.secondsSince(BeiDouTime(1088, 0.0))
        // Independent defining equation; do not compare only two inverse implementations.
        assertEquals(100.0 + .25 + 1e-5 * delta, delta, 1e-11)
        assertEquals(at, scale.utcAt(time))
        val before = scale.atUtcMillis(epoch - 1000)!!
        assertEquals(1087, before.week)
        assertEquals(epoch - 1000, scale.utcAt(before))
        assertEquals(.25 / (1 - 1e-5), scale.atUtcMillis(epoch)!!.secondsOfWeek, 1e-12)
    }

    @Test fun epochAndLeapSecondsComeFromIndependentUtcResourceRatherThanCurrentConstantOffset() {
        val originCorrection = RinexTimeSystemCorrection("BDUT", 0.0, 0.0, 0, 0, "", 7)
        val origin = RinexBeiDouTimeScale(utc, originCorrection, 604800.0)
        assertEquals(BeiDouTime(0, 0.0), origin.atUtcMillis(Instant.parse("2006-01-01T00:00:00Z").toEpochMilli()))
        assertNull(origin.atUtcMillis(Instant.parse("2005-12-31T23:59:59Z").toEpochMilli()))
        val leap = RinexBeiDouTimeScale(utc, RinexTimeSystemCorrection("BDUT", 0.0, 0.0, 0, 574, "", 7), 604800.0)
        val before = leap.atUtcMillis(Instant.parse("2016-12-31T23:59:59Z").toEpochMilli())!!
        val after = leap.atUtcMillis(Instant.parse("2017-01-01T00:00:00Z").toEpochMilli())!!
        assertEquals(2.0, after.secondsSince(before), 0.0)
    }

    @Test fun ageExpiredLeapTableNoninvertibleClockAndWrongCorrectionCannotBecomeValidTime() {
        val scale = RinexBeiDouTimeScale(utc, correction.copy(a0Seconds = 0.0, a1SecondsPerSecond = 0.0), 100.0)
        assertNotNull(scale.atUtcMillis(epoch + 100000))
        assertNull(scale.atUtcMillis(epoch + 100001))
        assertNull(scale.utcAt(BeiDouTime(1088, 101.0)))
        assertNull(scale.atUtcMillis(utc.expiresAtMillis))
        assertNull(RinexBeiDouTimeScale(utc, correction.copy(a1SecondsPerSecond = 1.0), 100.0).atUtcMillis(epoch))
        assertThrows(IllegalArgumentException::class.java) {
            RinexBeiDouTimeScale(utc, correction.copy(type = "GPUT"), 100.0)
        }
    }
}
