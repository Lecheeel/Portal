package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDate
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlonassBroadcastEvidenceTest {
    @Test fun explicitClockDatumAndOrbitEvidenceSurviveWireRoundTrip() {
        val record = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-reference.json")!!.readText())
            .jsonObject.getValue("records").jsonArray.first().jsonObject
        fun vector(key: String): GlonassCartesianVector {
            val values = record.getValue(key).jsonArray.map { it.jsonPrimitive.double }
            return GlonassCartesianVector(values[0], values[1], values[2])
        }
        val date = LocalDate.of(2023, 12, 31)
        fun coordinate(seconds: Double) = GlonassCoordinateEvidence(date.toEpochDay(), seconds)
        val clock = GlonassElapsedClockEvidence(9, "utc-su-test", coordinate(10_800.0), 5_000_000_000L,
            GlonassCoordinateEvidence(date.minusDays(1).toEpochDay(), 0.0),
            GlonassCoordinateEvidence(date.plusDays(1).toEpochDay(), 0.0))
        val orbit = GlonassBroadcastStateVector(1, GlonassReferenceFrame.PZ_90_02,
            vector("positionMeters"), vector("velocityMetersPerSecond"), vector("luniSolarAccelerationMetersPerSecondSquared"))
        val eph = GlonassBroadcastEphemerisEvidence(orbit, coordinate(10_800.0), coordinate(10_200.0), coordinate(11_400.0),
            healthBn = 0, healthLn = 0, accuracyFt = 1, ageDays = 0)
        val transform = GlonassEcefTransform(List(9) { index -> if (index % 4 == 0) 1.0 else 0.0 },
            GlonassCartesianVector(0.0, 0.0, 0.0), 0.0, 0.0)
        val evidence = GlonassBroadcastEvidence(clock, GlonassReferenceFrame.PZ_90_02, transform, listOf(eph))
        val capture = GnssCapture(deviceModel = "receiver", osVersion = "os", startedAtMillis = 0,
            endedAtMillis = 60_000, bootCount = 9,
            frames = (0..60).map { index -> GnssObservationFrame(index * 1000L,
                satellites = listOf(GnssSatellite(Constellation.GLONASS, 1, 30f, 90f, 35f, true, true, true))) },
            glonassBroadcastEvidence = evidence)
        val decoded = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
        assertEquals(evidence, decoded.glonassBroadcastEvidence)
        assertEquals(9, ReceiverBroadcastSkyModel(decoded).broadcastBootCount)
        assertTrue(decoded.glonassBroadcastEvidence!!.ephemerides.single().resolve(clock.domain()) != null)
    }
}
