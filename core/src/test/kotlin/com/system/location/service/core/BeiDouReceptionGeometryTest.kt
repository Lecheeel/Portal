package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class BeiDouReceptionGeometryTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/gnss/$name")!!.readText()).jsonObject
    private val records = resource("beidou-reference.json").getValue("records").jsonArray.map { it.jsonObject }
    private val cases = resource("beidou-reception-reference.json").getValue("cases").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(index: Int = 0): BeiDouBroadcastEphemeris {
        val r = records[index]
        fun n(name: String) = r.n(name)
        val toe = BeiDouTime(n("week").toInt(), n("toe"))
        return BeiDouBroadcastEphemeris(n("svid").toInt(), BeiDouOrbitType.valueOf(r.getValue("orbitType").jsonPrimitive.content),
            toe, BeiDouTime(toe.week, n("toc")), toe.plusSeconds(-7200.0), toe.plusSeconds(7200.0),
            n("sqrtA"), n("e"), n("i0"), n("omega0"), n("omega"), n("m0"), n("deltaN"), n("omegaDot"), n("iDot"),
            n("cuc"), n("cus"), n("crc"), n("crs"), n("cic"), n("cis"), n("af0"), n("af1"), n("af2"))
    }
    private val receiver = BdcsReceiver(EcefPosition(-2850000.0, 4650000.0, 3290000.0), GeodeticOrientation(31.2, 121.5))

    @Test fun signalClocksMatchIndependentTransmissionClockForEveryOrbitTypeAndLegacySignal() {
        cases.forEach { c ->
            val e = ephemeris(c.n("recordIndex").toInt())
            val xyz = c.getValue("receiverEcefMeters").jsonArray.map { it.jsonPrimitive.double }
            val point = BdcsReceiver(EcefPosition(xyz[0], xyz[1], xyz[2]),
                GeodeticOrientation(c.n("latitudeDegrees"), c.n("longitudeDegrees")))
            val clock = c.getValue("expected").jsonObject.n("clockBiasSeconds")
            val time = e.toe.plusSeconds(c.n("offsetSeconds"))
            val validity = BeiDouServiceValidity(e.validFrom, e.validUntil)
            for ((signal, tgd) in listOf(BeiDouLegacySignal.B1I to -34.5e-9, BeiDouLegacySignal.B2I to 23.4e-9)) {
                val result = if (e.orbitType == BeiDouOrbitType.GEO) {
                    BeiDouSignalReception.observe(BeiDouD2Decoded(e, 0, 5, -34.5e-9, 23.4e-9, e.toe, 3, 4),
                        time, point, signal, validity)
                } else {
                    BeiDouSignalReception.observe(BeiDouD1Decoded(e, 0, 5, -34.5e-9, 23.4e-9, e.toe, 3, 4),
                        time, point, signal, validity)
                }
                assertNotNull(result.reason, result.observation)
                assertEquals(clock - tgd, result.observation!!.signalClockBiasSeconds, 1e-16)
                assertEquals(BeiDouReceptionGeometry.observe(e, time, point).observation, result.observation!!.geometry)
            }
        }
    }

    @Test fun signalServiceAndOrbitLimitsApplyAtSolvedTransmissionRatherThanReception() {
        val e = ephemeris().let { it.copy(validFrom = it.toe.plusSeconds(-1.0), validUntil = it.toe.plusSeconds(1.0)) }
        val d = BeiDouD2Decoded(e, 0, 5, 0.0, 0.0, e.toe, 3, 4)
        val service = BeiDouServiceValidity(e.validFrom, e.validUntil)
        fun observe(decoded: BeiDouD2Decoded = d, validity: BeiDouServiceValidity = service) =
            BeiDouSignalReception.observe(decoded, e.validUntil.plusSeconds(0.05), receiver, BeiDouLegacySignal.B1I, validity)
        val result = observe()
        assertNotNull(result.reason, result.observation)
        assertTrue(result.observation!!.geometry.transmissionTime.secondsSince(service.until) < 0)
        assertNull(observe(d.copy(health = 1)).observation)
        assertNull(observe(d.copy(uraIndex = 15)).observation)
        assertNull(observe(d.copy(tgd1Seconds = Double.NaN)).observation)
        assertNull(observe(validity = BeiDouServiceValidity(e.validFrom, e.toe.plusSeconds(0.5))).observation)
    }

    @Test fun matchesIndependentCLightTimeRotationAndEnuForAllTwelveCases() {
        cases.forEach { c ->
            val e = ephemeris(c.n("recordIndex").toInt())
            val xyz = c.getValue("receiverEcefMeters").jsonArray.map { it.jsonPrimitive.double }
            val receiver = BdcsReceiver(EcefPosition(xyz[0], xyz[1], xyz[2]),
                GeodeticOrientation(c.n("latitudeDegrees"), c.n("longitudeDegrees")))
            val result = BeiDouReceptionGeometry.observe(e, e.toe.plusSeconds(c.n("offsetSeconds")), receiver)
            assertNotNull(result.reason, result.observation)
            val o = result.observation!!
            val expected = c.getValue("expected").jsonObject
            val values = mapOf("xMeters" to o.satelliteInReceptionEcef.xMeters,
                "yMeters" to o.satelliteInReceptionEcef.yMeters, "zMeters" to o.satelliteInReceptionEcef.zMeters,
                "rangeMeters" to o.lineOfSight.rangeMeters, "eastMeters" to o.lineOfSight.eastMeters,
                "northMeters" to o.lineOfSight.northMeters, "upMeters" to o.lineOfSight.upMeters)
            values.forEach { (name, value) -> assertEquals(name, expected.n(name), value, 1e-5) }
            assertEquals(expected.n("propagationSeconds"), o.propagationSeconds, 1e-12)
            assertEquals(expected.n("elevationDegrees"), o.lineOfSight.elevationDegrees, 1e-9)
            assertEquals(expected.n("azimuthDegrees"), o.lineOfSight.azimuthDegrees!!, 1e-9)
            assertEquals(expected.n("clockBiasSeconds"), o.clockBiasSeconds, 1e-16)
            assertEquals(o.lineOfSight.rangeMeters,
                o.propagationSeconds * BeiDouReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND, 0.00004)
        }
    }

    @Test fun validatesTransmissionEpochInsteadOfReceptionEpochAndRejectsOldFullWeeks() {
        val e = ephemeris().let { it.copy(validFrom = it.toe.plusSeconds(-1.0), validUntil = it.toe.plusSeconds(1.0)) }
        fun at(t: BeiDouTime) = BeiDouReceptionGeometry.observe(e, t, receiver)
        val after = at(e.validUntil.plusSeconds(0.05))
        assertNotNull(after.reason, after.observation)
        assertTrue(after.observation!!.transmissionTime.secondsSince(e.validUntil) < 0)
        assertNotNull(at(e.validFrom.plusSeconds(0.2)).observation)
        assertNull(at(e.validFrom.plusSeconds(0.001)).observation)
        assertNull(at(e.validUntil.plusSeconds(0.5)).observation)
        assertNull(at(e.toe.plusSeconds(604800.0)).observation)
    }

    @Test fun rotationIsAppliedExactlyOnceAndClockDoesNotChangeVacuumGeometry() {
        val e = ephemeris()
        val o = BeiDouReceptionGeometry.observe(e, e.toe, receiver).observation!!
        val transmitted = BeiDouBroadcastPropagator.propagate(e, o.transmissionTime).state!!
        assertTrue(hypot(transmitted.xMeters - o.satelliteInReceptionEcef.xMeters,
            transmitted.yMeters - o.satelliteInReceptionEcef.yMeters) > 1.0)
        assertEquals(transmitted.zMeters, o.satelliteInReceptionEcef.zMeters, 0.0)
        assertEquals(transmitted.clockBiasSeconds, o.clockBiasSeconds, 0.0)
        val shifted = BeiDouReceptionGeometry.observe(e.copy(af0Seconds = e.af0Seconds + 1e-6), e.toe, receiver).observation!!
        assertEquals(o.satelliteInReceptionEcef, shifted.satelliteInReceptionEcef)
        assertEquals(o.propagationSeconds, shifted.propagationSeconds, 0.0)
        assertEquals(o.clockBiasSeconds + 1e-6, shifted.clockBiasSeconds, 1e-18)
    }

    @Test fun fullBdtWeekBoundaryAndFirstEpochAreHandledWithoutWeekAliasing() {
        val base = ephemeris()
        val toe = BeiDouTime(1081, 604799.0)
        val e = base.copy(toe = toe, toc = toe, validFrom = toe.plusSeconds(-1.0), validUntil = toe.plusSeconds(2.0))
        val a = BeiDouReceptionGeometry.observe(e, toe.plusSeconds(0.999), receiver).observation!!
        val b = BeiDouReceptionGeometry.observe(e, toe.plusSeconds(1.001), receiver).observation!!
        assertTrue(kotlin.math.abs(b.lineOfSight.rangeMeters - a.lineOfSight.rangeMeters) < 10)
        assertTrue(b.transmissionTime.secondsSince(a.transmissionTime) in 0.0019..0.0021)
        val origin = BeiDouTime(0, 0.0)
        val first = base.copy(toe = origin, toc = origin, validFrom = origin, validUntil = origin.plusSeconds(1.0))
        assertNull(BeiDouReceptionGeometry.observe(first, origin, receiver).observation)
        assertNotNull(BeiDouReceptionGeometry.observe(first, origin.plusSeconds(0.2), receiver).observation)
    }
}
