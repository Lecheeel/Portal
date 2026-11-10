package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GpsReceptionGeometryTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.getResource("/gnss/$name")!!.readText()).jsonObject
    private val records = resource("gps-lnav-reference.json").getValue("records").jsonArray.map { it.jsonObject }
    private val cases = resource("gps-reception-reference.json").getValue("cases").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(index: Int = 0): GpsLnavEphemeris {
        val r = records[index]
        fun n(name: String) = r.n(name)
        val toe = GpsTime(n("week").toInt(), n("toe"))
        return GpsLnavEphemeris(n("prn").toInt(), toe, GpsTime(n("tocWeek").toInt(), n("tocSeconds")),
            toe.plusSeconds(-7200.0), toe.plusSeconds(7200.0), n("iode").toInt(), n("iode").toInt(),
            n("iodc").toInt(), n("health").toInt(), n("sqrtA"), n("e"), n("i0"), n("omega0"), n("omega"), n("m0"),
            n("deltaN"), n("omegaDot"), n("iDot"), n("cuc"), n("cus"), n("crc"), n("crs"), n("cic"), n("cis"),
            n("af0"), n("af1"), n("af2"), n("tgd"))
    }

    @Test fun matchesIndependentCLightTimeEciRotationAndEnuReferenceAtNineReceptionPoints() {
        cases.forEach { c ->
            val e = ephemeris(c.n("recordIndex").toInt())
            val result = GpsReceptionGeometry.observe(e, e.toe.plusSeconds(c.n("offsetSeconds")),
                Wgs84(c.n("latitude"), c.n("longitude")), c.n("ellipsoidHeightMeters"))
            assertNotNull(result.reason, result.observation)
            val observation = result.observation!!
            val expected = c.getValue("expected").jsonObject
            val values = mapOf("xMeters" to observation.satelliteInReceptionEcef.xMeters,
                "yMeters" to observation.satelliteInReceptionEcef.yMeters,
                "zMeters" to observation.satelliteInReceptionEcef.zMeters,
                "rangeMeters" to observation.lineOfSight.rangeMeters,
                "eastMeters" to observation.lineOfSight.eastMeters,
                "northMeters" to observation.lineOfSight.northMeters, "upMeters" to observation.lineOfSight.upMeters)
            values.forEach { (name, value) -> assertEquals(name, expected.n(name), value, 1e-5) }
            assertEquals(expected.n("propagationSeconds"), observation.propagationSeconds, 1e-12)
            assertEquals(expected.n("elevationDegrees"), observation.lineOfSight.elevationDegrees, 1e-9)
            assertEquals(expected.n("azimuthDegrees"), observation.lineOfSight.azimuthDegrees!!, 1e-9)
            assertEquals(expected.n("clockBiasSeconds"), observation.clockBiasSeconds, 1e-16)
            assertEquals(observation.lineOfSight.rangeMeters,
                observation.propagationSeconds * GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND, 0.00004)
        }
    }

    @Test fun receptionCanFollowFitEndIfActualTransmissionIsStillInsideFit() {
        val e = ephemeris().let { it.copy(validFrom = it.toe.plusSeconds(-1.0), validUntil = it.toe.plusSeconds(1.0)) }
        val point = Wgs84(31.2304, 121.4737)
        fun at(time: GpsTime) = GpsReceptionGeometry.observe(e, time, point, 50.0)
        val after = at(e.validUntil.plusSeconds(0.05))
        assertNotNull(after.reason, after.observation)
        assertTrue(after.observation!!.transmissionTime.secondsSince(e.validUntil) < 0)
        assertNotNull(at(e.validFrom.plusSeconds(0.15)).observation)
        assertNull(at(e.validFrom.plusSeconds(0.001)).observation)
        assertNull(at(e.validUntil.plusSeconds(0.5)).observation)
        assertNull(at(e.toe.plusSeconds(604800.0)).observation)
        assertNull(GpsReceptionGeometry.observe(e.copy(health = 1), e.toe, point, 50.0).observation)
    }

    @Test fun groupDelayChangesSignalClockOnlyAndEarthRotationIsAppliedOnce() {
        val e = ephemeris()
        val observer = Wgs84(31.2304, 121.4737)
        val a = GpsReceptionGeometry.observe(e, e.toe, observer, 50.0).observation!!
        val b = GpsReceptionGeometry.observe(e.copy(tgdSeconds = e.tgdSeconds + 1e-8), e.toe, observer, 50.0).observation!!
        assertEquals(a.satelliteInReceptionEcef, b.satelliteInReceptionEcef)
        assertEquals(a.clockBiasSeconds, b.clockBiasSeconds, 0.0)
        assertEquals(a.l1CaClockBiasSeconds - 1e-8, b.l1CaClockBiasSeconds, 1e-19)
        val transmitted = GpsBroadcastPropagator.propagate(e, a.transmissionTime).state!!
        val displacement = kotlin.math.hypot(transmitted.xMeters - a.satelliteInReceptionEcef.xMeters,
            transmitted.yMeters - a.satelliteInReceptionEcef.yMeters)
        assertTrue("nonzero flight time must rotate the ECEF axes", displacement > 1.0)
        assertEquals(transmitted.zMeters, a.satelliteInReceptionEcef.zMeters, 0.0)
    }

    @Test fun receptionAcrossFullWeekBoundaryRemainsContinuous() {
        val base = ephemeris()
        val toe = GpsTime(1316, 604799.0)
        val e = base.copy(toe = toe, toc = toe, validFrom = toe.plusSeconds(-7200.0), validUntil = toe.plusSeconds(7200.0))
        val observer = Wgs84(20.0, 40.0)
        val before = GpsReceptionGeometry.observe(e, toe.plusSeconds(0.999), observer, 0.0).observation!!
        val after = GpsReceptionGeometry.observe(e, toe.plusSeconds(1.001), observer, 0.0).observation!!
        assertTrue(kotlin.math.abs(after.lineOfSight.rangeMeters - before.lineOfSight.rangeMeters) < 10)
        assertTrue(after.transmissionTime.secondsSince(before.transmissionTime) in 0.0019..0.0021)
    }
}
