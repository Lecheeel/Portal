package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class GalileoReceptionGeometryTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/gnss/$name")!!.readText()).jsonObject
    private val records = resource("galileo-reference.json").getValue("records").jsonArray.map { it.jsonObject }
    private val cases = resource("galileo-reception-reference.json").getValue("cases").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(index: Int = 0): GalileoBroadcastEphemeris {
        val r = records[index]
        fun n(name: String) = r.n(name)
        val toe = GalileoTime(n("gstWeek").toInt(), n("toe"))
        return GalileoBroadcastEphemeris(n("svid").toInt(), toe, GalileoTime(toe.week, n("toc")),
            toe.plusSeconds(-7200.0), toe.plusSeconds(7200.0), n("iodnav").toInt(),
            GalileoClockReference.valueOf(r.getValue("clockReference").jsonPrimitive.content),
            n("sqrtA"), n("e"), n("i0"), n("omega0"), n("omega"), n("m0"), n("deltaN"), n("omegaDot"), n("iDot"),
            n("cuc"), n("cus"), n("crc"), n("crs"), n("cic"), n("cis"), n("af0"), n("af1"), n("af2"))
    }
    private val receiver = GtrfReceiver(EcefPosition(-2850000.0, 4650000.0, 3290000.0), GeodeticOrientation(31.2, 121.5))

    @Test fun signalClocksUseIndependentTransmissionClockAndExactBroadcastFrequencyRatios() {
        cases.forEach { c ->
            val e = ephemeris(c.n("recordIndex").toInt())
            val xyz = c.getValue("receiverEcefMeters").jsonArray.map { it.jsonPrimitive.double }
            val point = GtrfReceiver(EcefPosition(xyz[0], xyz[1], xyz[2]),
                GeodeticOrientation(c.n("latitudeDegrees"), c.n("longitudeDegrees")))
            val time = e.toe.plusSeconds(c.n("offsetSeconds"))
            val healthy = GalileoSignalStatus(0, 0)
            val bgd = Math.scalb(5.0, -32)
            val clock = c.getValue("expected").jsonObject.n("clockBiasSeconds")
            if (e.clockReference == GalileoClockReference.INAV_E1_E5B) {
                val d = DecodedGalileoInav(e, e.toe, healthy, healthy, 107, 0.0, bgd)
                for ((signal, ratio) in listOf(GalileoOpenServiceSignal.E1 to 1.0,
                    GalileoOpenServiceSignal.E5B to (5929.0 / 3481))) {
                    val result = GalileoSignalReception.observe(d, time, point, signal)
                    assertNotNull(result.reason, result.observation)
                    assertEquals(clock - ratio * bgd, result.observation!!.signalClockBiasSeconds, 1e-16)
                }
            } else {
                val d = DecodedGalileoFnav(e, e.toe, healthy, 107, bgd)
                val result = GalileoSignalReception.observe(d, time, point, GalileoOpenServiceSignal.E5A)
                assertNotNull(result.reason, result.observation)
                assertEquals(clock - 23716.0 / 13225 * bgd, result.observation!!.signalClockBiasSeconds, 1e-16)
            }
        }
    }

    @Test fun signalUsabilityAndTransmissionValidityAreRequiredTogether() {
        val e = ephemeris().let { it.copy(clockReference = GalileoClockReference.INAV_E1_E5B,
            validFrom = it.toe.plusSeconds(-1.0), validUntil = it.toe.plusSeconds(1.0)) }
        val healthy = GalileoSignalStatus(0, 0)
        val d = DecodedGalileoInav(e, e.toe, healthy, healthy, 107, 0.0, 0.0)
        fun observe(value: DecodedGalileoInav, signal: GalileoOpenServiceSignal = GalileoOpenServiceSignal.E1) =
            GalileoSignalReception.observe(value, e.validUntil.plusSeconds(0.05), receiver, signal)
        assertNotNull(observe(d).observation)
        assertNull(observe(d.copy(e1Status = GalileoSignalStatus(1, 0))).observation)
        assertNull(observe(d.copy(e1Status = GalileoSignalStatus(0, 1))).observation)
        assertNotNull(observe(d.copy(e1Status = GalileoSignalStatus(1, 0)), GalileoOpenServiceSignal.E5B).observation)
        assertNull(observe(d, GalileoOpenServiceSignal.E5A).observation)
        assertNull(observe(d.copy(sisaIndex = 126)).observation)
        assertNull(observe(d.copy(bgdE1E5bSeconds = Double.NaN)).observation)
        assertNull(GalileoSignalReception.observe(d, e.validUntil.plusSeconds(0.5), receiver,
            GalileoOpenServiceSignal.E1).observation)
    }

    @Test fun matchesIndependentCLightTimeRotationAndEnuForAllTwelveCases() {
        cases.forEach { c ->
            val e = ephemeris(c.n("recordIndex").toInt())
            val xyz = c.getValue("receiverEcefMeters").jsonArray.map { it.jsonPrimitive.double }
            val receiver = GtrfReceiver(EcefPosition(xyz[0], xyz[1], xyz[2]),
                GeodeticOrientation(c.n("latitudeDegrees"), c.n("longitudeDegrees")))
            val result = GalileoReceptionGeometry.observe(e, e.toe.plusSeconds(c.n("offsetSeconds")), receiver)
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
            assertEquals(e.clockReference, o.clockReference)
            assertEquals(o.lineOfSight.rangeMeters,
                o.propagationSeconds * GalileoReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND, 0.00004)
        }
    }

    @Test fun validatesTransmissionEpochInsteadOfReceptionEpochAndRejectsOldFullWeeks() {
        val e = ephemeris().let { it.copy(validFrom = it.toe.plusSeconds(-1.0), validUntil = it.toe.plusSeconds(1.0)) }
        fun at(t: GalileoTime) = GalileoReceptionGeometry.observe(e, t, receiver)
        val after = at(e.validUntil.plusSeconds(0.05))
        assertNotNull(after.reason, after.observation)
        assertTrue(after.observation!!.transmissionTime.secondsSince(e.validUntil) < 0)
        assertNotNull(at(e.validFrom.plusSeconds(0.15)).observation)
        assertNull(at(e.validFrom.plusSeconds(0.001)).observation)
        assertNull(at(e.validUntil.plusSeconds(0.5)).observation)
        assertNull(at(e.toe.plusSeconds(604800.0)).observation)
    }

    @Test fun rotationIsAppliedExactlyOnceAndClockReferenceIsPreservedWithoutBgd() {
        val e = ephemeris()
        val o = GalileoReceptionGeometry.observe(e, e.toe, receiver).observation!!
        val transmitted = GalileoBroadcastPropagator.propagate(e, o.transmissionTime).state!!
        assertTrue(hypot(transmitted.xMeters - o.satelliteInReceptionEcef.xMeters,
            transmitted.yMeters - o.satelliteInReceptionEcef.yMeters) > 1.0)
        assertEquals(transmitted.zMeters, o.satelliteInReceptionEcef.zMeters, 0.0)
        assertEquals(transmitted.clockBiasSeconds, o.clockBiasSeconds, 0.0)
        val shifted = GalileoReceptionGeometry.observe(e.copy(af0Seconds = e.af0Seconds + 1e-6), e.toe, receiver).observation!!
        assertEquals(o.satelliteInReceptionEcef, shifted.satelliteInReceptionEcef)
        assertEquals(o.propagationSeconds, shifted.propagationSeconds, 0.0)
        assertEquals(o.clockBiasSeconds + 1e-6, shifted.clockBiasSeconds, 1e-18)
    }

    @Test fun fullGstWeekBoundaryAndFirstEpochAreHandledWithoutWeekAliasing() {
        val base = ephemeris()
        val toe = GalileoTime(1081, 604799.0)
        val e = base.copy(toe = toe, toc = toe, validFrom = toe.plusSeconds(-1.0), validUntil = toe.plusSeconds(2.0))
        val a = GalileoReceptionGeometry.observe(e, toe.plusSeconds(0.999), receiver).observation!!
        val b = GalileoReceptionGeometry.observe(e, toe.plusSeconds(1.001), receiver).observation!!
        assertTrue(kotlin.math.abs(b.lineOfSight.rangeMeters - a.lineOfSight.rangeMeters) < 10)
        assertTrue(b.transmissionTime.secondsSince(a.transmissionTime) in 0.0019..0.0021)
        val origin = GalileoTime(0, 0.0)
        val first = base.copy(toe = origin, toc = origin, validFrom = origin, validUntil = origin.plusSeconds(1.0))
        assertNull(GalileoReceptionGeometry.observe(first, origin, receiver).observation)
        assertNotNull(GalileoReceptionGeometry.observe(first, origin.plusSeconds(0.2), receiver).observation)
    }
}
