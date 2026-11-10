package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GpsCaptureEphemeridesTest {
    private val frames = Json.parseToJsonElement(javaClass.getResource("/gnss/gps-lnav-words-reference.json")!!
        .readText()).jsonObject.getValue("subframes").jsonArray.take(3).map { it.jsonObject }
    private fun capture(): GnssCapture {
        val navigation = frames.map { f ->
            val id = f.getValue("id").jsonPrimitive.int
            val bytes = f.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            GnssNavigationObservation(id * 6000L, 0x0101, 1, 1, -1, id,
                Base64.getEncoder().encodeToString(bytes), id * 6_000_000_000L)
        }
        val measurements = navigation.mapIndexed { index, nav ->
            val seconds = frames[index].getValue("nextTowSeconds").jsonPrimitive.long
            val absolute = 1316L * 604800_000_000_000L + seconds * 1_000_000_000L
            val hardware = nav.capturedElapsedRealtimeNanos!!
            GnssMeasurementBatch(nav.capturedAtMillis, hardware, hardware - absolute, 0.0, 0, emptyList(),
                timeUncertaintyNanos = 0.0, biasUncertaintyNanos = 10.0,
                elapsedRealtimeNanos = hardware, elapsedRealtimeUncertaintyNanos = 10.0,
                capturedElapsedRealtimeNanos = hardware + 1_000_000)
        }
        return GnssCapture(deviceModel = "device", osVersion = "os", startedAtMillis = 1, endedAtMillis = 20000,
            frames = listOf(GnssObservationFrame(1, satellites = emptyList())),
            measurements = measurements, navigation = navigation)
    }

    @Test fun completeCaptureAssociatesMonotonicEpochAndDecodedHowWithoutUsingUtcOffset() {
        val capture = capture()
        val decoded = GpsCaptureEphemerisDecoder.decode(capture)
        assertEquals(1, decoded.decoded.size)
        assertEquals(GpsTime(1316, 525600.0), decoded.decoded.single().ephemeris.toe)
        val differentWallClock = capture.copy(navigation = capture.navigation.map { it.copy(capturedAtMillis = it.capturedAtMillis + 1000) })
        assertEquals(decoded.decoded, GpsCaptureEphemerisDecoder.decode(differentWallClock).decoded)
    }

    @Test fun missingRawTimingOrUncertaintyCannotBecomeTrustedEraEvidence() {
        val c = capture()
        val invalid = listOf(c.copy(measurements = emptyList()),
            c.copy(measurements = c.measurements.map { it.copy(biasUncertaintyNanos = null) }),
            c.copy(measurements = c.measurements.map { it.copy(elapsedRealtimeNanos = null) }),
            c.copy(measurements = c.measurements.map { it.copy(biasUncertaintyNanos = 2e9) }),
            c.copy(measurements = c.measurements.map { it.copy(fullBiasNanos = null) }),
            c.copy(navigation = c.navigation.map { it.copy(capturedElapsedRealtimeNanos = null) }))
        invalid.forEach { assertTrue(GpsCaptureEphemerisDecoder.decode(it).decoded.isEmpty()) }
    }

    @Test fun discontinuityOrConflictingClockEpochCannotJoinSubframes() {
        val c = capture()
        val reset = c.copy(measurements = c.measurements.mapIndexed { index, batch ->
            batch.copy(hardwareClockDiscontinuityCount = if (index == 1) 1 else 0)
        })
        assertTrue(GpsCaptureEphemerisDecoder.decode(reset).decoded.isEmpty())
        val inconsistent = c.copy(measurements = c.measurements.mapIndexed { index, batch ->
            if (index == 1) batch.copy(fullBiasNanos = batch.fullBiasNanos!! - 5_000_000_000L) else batch
        })
        assertTrue(GpsCaptureEphemerisDecoder.decode(inconsistent).decoded.isEmpty())
        assertTrue(GpsCaptureEphemerisDecoder.decode(c.copy(navigation = c.navigation.take(2))).decoded.isEmpty())
    }
}
