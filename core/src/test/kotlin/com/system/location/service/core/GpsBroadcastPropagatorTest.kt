package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GpsBroadcastPropagatorTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/gps-lnav-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(record: JsonObject = records.first()): GpsLnavEphemeris {
        fun n(name: String) = record.number(name)
        val toe = GpsTime(n("week").toInt(), n("toe"))
        return GpsLnavEphemeris(n("prn").toInt(), toe, GpsTime(n("tocWeek").toInt(), n("tocSeconds")),
            toe.plusSeconds(-n("fitHours") * 1800), toe.plusSeconds(n("fitHours") * 1800),
            n("iode").toInt(), n("iode").toInt(), n("iodc").toInt(), n("health").toInt(),
            n("sqrtA"), n("e"), n("i0"), n("omega0"), n("omega"), n("m0"), n("deltaN"), n("omegaDot"), n("iDot"),
            n("cuc"), n("cus"), n("crc"), n("crs"), n("cic"), n("cis"), n("af0"), n("af1"), n("af2"), n("tgd"))
    }

    @Test fun matchesIndependentPinnedRtkLibPositionsAndRelativisticClockForNineCases() {
        for (record in records) {
            val e = ephemeris(record)
            for (expected in record.getValue("expected").jsonArray.map { it.jsonObject }) {
                val result = GpsBroadcastPropagator.propagate(e, e.toe.plusSeconds(expected.number("offsetSeconds")))
                assertNotNull(result.reason, result.state)
                val state = result.state!!
                // 10 micrometres for numeric agreement; this is not a claim about broadcast orbit accuracy.
                assertEquals(expected.number("xMeters"), state.xMeters, 1e-5)
                assertEquals(expected.number("yMeters"), state.yMeters, 1e-5)
                assertEquals(expected.number("zMeters"), state.zMeters, 1e-5)
                assertEquals(expected.number("clockBiasSeconds"), state.clockBiasSeconds, 1e-16)
            }
        }
    }

    @Test fun rejectsUnhealthyStaleOrUnvalidatedClockEpochWithoutAliasingWeek() {
        val e = ephemeris()
        assertNull(GpsBroadcastPropagator.propagate(e.copy(health = 1), e.toe).state)
        assertNull(GpsBroadcastPropagator.propagate(e, e.toe.plusSeconds(604800.0)).state)
        assertNull(GpsBroadcastPropagator.propagate(e, e.validUntil.plusSeconds(0.001)).state)
        assertNull(GpsBroadcastPropagator.propagate(e, e.validFrom.plusSeconds(-0.001)).state)
        assertNull(GpsBroadcastPropagator.propagate(e.copy(toc = e.toc.plusSeconds(-604800.0)), e.toe).state)
    }

    @Test fun fullWeekCrossoverKeepsAContinuousPhysicalTrajectory() {
        val e = ephemeris()
        val toe = GpsTime(e.toe.week, 604799.0)
        val crossed = e.copy(toe = toe, toc = toe, validFrom = toe.plusSeconds(-7200.0), validUntil = toe.plusSeconds(7200.0))
        val before = GpsBroadcastPropagator.propagate(crossed, toe.plusSeconds(0.999)).state!!
        val after = GpsBroadcastPropagator.propagate(crossed, toe.plusSeconds(1.001)).state!!
        val distance = kotlin.math.sqrt((after.xMeters-before.xMeters).let { it*it } +
            (after.yMeters-before.yMeters).let { it*it } + (after.zMeters-before.zMeters).let { it*it })
        assertTrue("2ms trajectory step must remain finite and continuous: $distance", distance in 0.1..20.0)
    }

    @Test fun mismatchedIssuesAndMalformedParametersCannotFormAnEphemeris() {
        val e = ephemeris()
        assertThrows(IllegalArgumentException::class.java) { e.copy(iode3 = (e.iode2 + 1) % 256) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(iodc = (e.iodc + 1) % 1024) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(eccentricity = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(sqrtSemiMajorAxis = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(crcMeters = Double.POSITIVE_INFINITY) }
    }
}
