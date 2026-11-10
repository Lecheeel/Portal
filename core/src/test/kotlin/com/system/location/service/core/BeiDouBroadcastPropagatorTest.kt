package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BeiDouBroadcastPropagatorTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/beidou-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(index: Int = 0): BeiDouBroadcastEphemeris {
        val r = records[index]
        fun n(name: String) = r.n(name)
        val toe = BeiDouTime(n("week").toInt(), n("toe"))
        return BeiDouBroadcastEphemeris(n("svid").toInt(), BeiDouOrbitType.valueOf(r.getValue("orbitType").jsonPrimitive.content),
            toe, BeiDouTime(toe.week, n("toc")), toe.plusSeconds(-3600.0), toe.plusSeconds(3600.0),
            n("sqrtA"), n("e"), n("i0"), n("omega0"), n("omega"), n("m0"), n("deltaN"), n("omegaDot"), n("iDot"),
            n("cuc"), n("cus"), n("crc"), n("crs"), n("cic"), n("cis"), n("af0"), n("af1"), n("af2"))
    }

    @Test fun matchesTwelveIndependentCSyntheticVectorsForAllThreeOrbitTypes() {
        records.forEachIndexed { index, r ->
            val e = ephemeris(index)
            r.getValue("expected").jsonArray.map { it.jsonObject }.forEach { vector ->
                val result = BeiDouBroadcastPropagator.propagate(e, e.toe.plusSeconds(vector.n("offsetSeconds")))
                assertNotNull(result.reason, result.state)
                val state = result.state!!
                assertEquals(vector.n("xMeters"), state.xMeters, 1e-5)
                assertEquals(vector.n("yMeters"), state.yMeters, 1e-5)
                assertEquals(vector.n("zMeters"), state.zMeters, 1e-5)
                assertEquals(vector.n("clockBiasSeconds"), state.clockBiasSeconds, 1e-16)
            }
        }
    }

    @Test fun geoRotationMatchesAnalyticCircularOrbitAndNeverInfersTypeFromSvid() {
        val base = ephemeris().copy(eccentricity = 0.0, inclinationRad = 0.0, ascendingNodeRad = 0.0,
            argumentOfPerigeeRad = 0.0, meanAnomalyRad = PI / 2, deltaMeanMotionRadPerSecond = 0.0,
            ascendingNodeRateRadPerSecond = 0.0, inclinationRateRadPerSecond = 0.0,
            cucRad = 0.0, cusRad = 0.0, crcMeters = 0.0, crsMeters = 0.0, cicRad = 0.0, cisRad = 0.0)
        val origin = BeiDouTime(700, 0.0)
        val e = base.copy(toe = origin, toc = origin, validFrom = origin, validUntil = origin.plusSeconds(3600.0))
        val state = BeiDouBroadcastPropagator.propagate(e, origin).state!!
        val radius = e.sqrtSemiMajorAxis * e.sqrtSemiMajorAxis
        assertEquals(0.0, state.xMeters, 1e-8)
        assertEquals(radius * cos(Math.toRadians(5.0)), state.yMeters, 1e-8)
        assertEquals(radius * sin(Math.toRadians(5.0)), state.zMeters, 1e-8)
        assertEquals(state, BeiDouBroadcastPropagator.propagate(e.copy(svid = 59), origin).state)
        val meo = BeiDouBroadcastPropagator.propagate(e.copy(orbitType = BeiDouOrbitType.MEO), origin).state!!
        assertEquals(0.0, meo.zMeters, 0.0)
        assertEquals(radius, meo.yMeters, 1e-8)
        assertEquals(meo, BeiDouBroadcastPropagator.propagate(e.copy(orbitType = BeiDouOrbitType.IGSO), origin).state)
    }

    @Test fun validityIsAbsoluteWithoutRevivingAStaleWeekAndFullWeekMotionIsContinuous() {
        val base = ephemeris()
        assertNull(BeiDouBroadcastPropagator.propagate(base, base.toe.plusSeconds(604800.0)).state)
        assertNull(BeiDouBroadcastPropagator.propagate(base, base.validFrom.plusSeconds(-0.001)).state)
        assertNull(BeiDouBroadcastPropagator.propagate(base, base.validUntil.plusSeconds(0.001)).state)
        assertNotNull(BeiDouBroadcastPropagator.propagate(base, base.validUntil).state)
        val toe = BeiDouTime(700, 604799.0)
        val e = base.copy(toe = toe, toc = toe, validFrom = toe.plusSeconds(-3600.0), validUntil = toe.plusSeconds(3600.0))
        val a = BeiDouBroadcastPropagator.propagate(e, toe.plusSeconds(0.999)).state!!
        val b = BeiDouBroadcastPropagator.propagate(e, toe.plusSeconds(1.001)).state!!
        assertTrue(hypot(hypot(a.xMeters - b.xMeters, a.yMeters - b.yMeters), a.zMeters - b.zMeters) < 20)
    }

    @Test fun invalidFieldsAndNumericalOverflowCannotProduceCoordinates() {
        val e = ephemeris()
        assertThrows(IllegalArgumentException::class.java) { e.copy(svid = 64) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(sqrtSemiMajorAxis = 8192.0) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(eccentricity = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(eccentricity = 0.5) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(cucRad = Double.POSITIVE_INFINITY) }
        assertNull(BeiDouBroadcastPropagator.propagate(e.copy(inclinationRateRadPerSecond = Double.MAX_VALUE),
            e.toe.plusSeconds(900.0)).state)
        assertNull(BeiDouBroadcastPropagator.propagate(e.copy(crcMeters = -Double.MAX_VALUE,
            crsMeters = Double.MAX_VALUE), e.toe.plusSeconds(900.0)).state)
    }
}
