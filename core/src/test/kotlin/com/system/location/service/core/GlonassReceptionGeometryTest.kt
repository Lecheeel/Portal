package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GlonassReceptionGeometryTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private val cases = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-reception-reference.json")!!.readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun JsonObject.xyz(name: String): GlonassCartesianVector {
        val v = getValue(name).jsonArray.map { it.jsonPrimitive.double }
        return GlonassCartesianVector(v[0], v[1], v[2])
    }
    private val date = LocalDate.of(2013, 1, 1)
    private val domain = GlonassTimeDomain("synthetic-continuous-clock-evidence", GlonassCalendarCoordinate(date.minusDays(1), 0.0),
        GlonassCalendarCoordinate(date.plusDays(1), 0.0))
    private val reference = domain.at(GlonassCalendarCoordinate(date, 10800.0))!!
    private fun ephemeris(index: Int): GlonassOrbitalEphemeris {
        val r = records[index]
        return GlonassOrbitalEphemeris(GlonassBroadcastStateVector(r.n("slot").toInt(), GlonassReferenceFrame.PZ_90_02,
            r.xyz("positionMeters"), r.xyz("velocityMetersPerSecond"), r.xyz("luniSolarAccelerationMetersPerSecondSquared")),
            reference, reference.plusSeconds(-899.0)!!, reference.plusSeconds(899.0)!!)
    }
    private val receiver = GlonassReceiver(GlonassReferenceFrame.PZ_90_02,
        EcefPosition(6378136.0, 0.0, 0.0), GeodeticOrientation(0.0, 0.0))

    @Test fun matchesTwelveIndependentCFixedPointLightTimeAndEnuCases() {
        cases.forEach { case ->
            val e = ephemeris(case.n("recordIndex").toInt())
            val r = case.xyz("receiverEcefMeters")
            val station = receiver.copy(position = EcefPosition(r.x, r.y, r.z),
                orientation = GeodeticOrientation(case.n("latitudeDegrees"), case.n("longitudeDegrees")))
            val reception = reference.plusSeconds(case.n("offsetSeconds"))!!
            val result = GlonassReceptionGeometry.observe(e, reception, station)
            assertNotNull(result.reason, result.observation)
            val o = result.observation!!
            val expected = case.getValue("expected").jsonObject
            assertEquals(expected.n("propagationSeconds"), o.propagationSeconds, 2e-13)
            assertEquals(expected.n("xMeters"), o.satelliteInReceptionEcef.xMeters, 1e-4)
            assertEquals(expected.n("yMeters"), o.satelliteInReceptionEcef.yMeters, 1e-4)
            assertEquals(expected.n("zMeters"), o.satelliteInReceptionEcef.zMeters, 1e-4)
            assertEquals(expected.n("rangeMeters"), o.lineOfSight.rangeMeters, 1e-4)
            assertEquals(expected.n("eastMeters"), o.lineOfSight.eastMeters, 1e-4)
            assertEquals(expected.n("northMeters"), o.lineOfSight.northMeters, 1e-4)
            assertEquals(expected.n("upMeters"), o.lineOfSight.upMeters, 1e-4)
            assertEquals(expected.n("elevationDegrees"), o.lineOfSight.elevationDegrees, 1e-9)
            assertEquals(expected.n("azimuthDegrees"), o.lineOfSight.azimuthDegrees!!, 1e-9)
            assertEquals(o.propagationSeconds, reception.secondsSince(o.transmissionTime)!!, 1e-10)
            assertTrue(abs(o.lineOfSight.rangeMeters / GlonassReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND - o.propagationSeconds) <= 1e-13)
        }
    }

    @Test fun applicabilityIsCheckedAtEmissionIncludingWhenReceptionIsAfterTheEnd() {
        val e = ephemeris(0)
        val justAfterEnd = e.validUntil.plusSeconds(0.01)!!
        val valid = GlonassReceptionGeometry.observe(e, justAfterEnd, receiver)
        assertNotNull(valid.reason, valid.observation)
        val observation = valid.observation!!
        assertTrue(observation.transmissionTime.secondsSince(e.validUntil)!! < 0)
        assertNull(GlonassReceptionGeometry.observe(e, e.validUntil.plusSeconds(1.0)!!, receiver).observation)
        assertNull(GlonassReceptionGeometry.observe(e, e.validFrom, receiver).observation)
        assertNull(GlonassReceptionGeometry.observe(e, e.validFrom.plusSeconds(-0.01)!!, receiver).observation)
        assertNotNull(GlonassReceptionGeometry.observe(e, e.validFrom.plusSeconds(1.0)!!, receiver).observation)
        // An emission at an exact applicability endpoint remains valid.
        val delay = observation.propagationSeconds
        val edge = e.copy(validUntil = observation.transmissionTime)
        val edgeResult = GlonassReceptionGeometry.observe(edge, justAfterEnd, receiver)
        assertNotNull(edgeResult.reason, edgeResult.observation)
        assertTrue(delay > 0)
    }

    @Test fun requiresSameCoordinateRealizationAndContinuousClockDomain() {
        val e = ephemeris(0)
        assertNull(GlonassReceptionGeometry.observe(e, reference, receiver.copy(referenceFrame = GlonassReferenceFrame.PZ_90_11)).observation)
        val other = GlonassTimeDomain(domain.evidenceId, domain.from, domain.until)
        assertNull(GlonassReceptionGeometry.observe(e, other.at(reference.coordinate)!!, receiver).observation)
        // Labels are explicitly reused solely to verify numerical geometry across midnight.
        val midnight = domain.at(GlonassCalendarCoordinate(date, 0.0))!!
        val reepoch = e.copy(referenceTime = midnight, validFrom = midnight.plusSeconds(-899.0)!!, validUntil = midnight.plusSeconds(899.0)!!)
        val a = GlonassReceptionGeometry.observe(reepoch, midnight, receiver).observation!!
        val b = GlonassReceptionGeometry.observe(e, reference, receiver).observation!!
        assertEquals(a.lineOfSight.rangeMeters, b.lineOfSight.rangeMeters, 1e-5)
        assertEquals(date.minusDays(1), a.transmissionTime.coordinate.date)
    }
}
