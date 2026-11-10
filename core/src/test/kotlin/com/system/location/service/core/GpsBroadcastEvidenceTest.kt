package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GpsBroadcastEvidenceTest {
    // Synthetic parity-protected words derived from real RINEX parameters, not receiver evidence.
    private fun capture(): GnssCapture {
        val words = Json.parseToJsonElement(javaClass.getResource("/gnss/gps-lnav-words-reference.json")!!
            .readText()).jsonObject.getValue("subframes").jsonArray.take(3).map { it.jsonObject }
        val tow = words.first().getValue("nextTowSeconds").jsonPrimitive.long - 6
        val navigation = words.map { f ->
            val id = f.getValue("id").jsonPrimitive.int
            val bytes = f.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            GnssNavigationObservation(id * 6000L, 0x0101, 1, 1, -1, id,
                Base64.getEncoder().encodeToString(bytes), (100 + id * 6) * 1_000_000_000L)
        }
        val measurements = (0..6).map { i ->
            val elapsed = (100 + i * 6) * 1_000_000_000L
            val gps = 1316L * 604800_000_000_000L + (tow + i * 6) * 1_000_000_000L
            GnssMeasurementBatch(i * 6000L, elapsed, elapsed - gps, 0.0, 0, emptyList(),
                timeUncertaintyNanos = 10.0, biasUncertaintyNanos = 10.0,
                elapsedRealtimeNanos = elapsed, elapsedRealtimeUncertaintyNanos = 10.0,
                capturedElapsedRealtimeNanos = elapsed + 1_000_000L)
        }
        return GnssCapture(deviceModel = "test", osVersion = "test", startedAtMillis = 0, endedAtMillis = 36000,
            frames = listOf(GnssObservationFrame(0, satellites = listOf(
                GnssSatellite(Constellation.GPS, 1, 30f, 100f, 35f, true, true, true)))),
            measurements = measurements, navigation = navigation, bootCount = 42)
    }

    @Test fun compactEvidenceRequiresBothClockAndObservedParityCheckedOrbit() {
        val c = capture()
        val e = GpsBroadcastEvidence.fromCapture(c)!!
        assertEquals(1, e.ephemerides.single().ephemeris.svid)
        assertEquals(42, e.clock.bootCount)
        assertEquals(e, Json.decodeFromString<GpsBroadcastEvidence>(Json.encodeToString(e)))
        assertNull(GpsBroadcastEvidence.fromCapture(c.copy(bootCount = null)))
        assertNull(GpsBroadcastEvidence.fromCapture(c.copy(navigation = c.navigation.take(2))))
        assertNull(GpsBroadcastEvidence.fromCapture(c.copy(frames = listOf(GnssObservationFrame(0, satellites = emptyList())))))
    }

    @Test fun compactInputCannotContainDuplicatesUnhealthyUnknownAccuracyOrFutureEvidence() {
        val e = GpsBroadcastEvidence.fromCapture(capture())!!
        val d = e.ephemerides.single()
        assertThrows(IllegalArgumentException::class.java) { e.copy(ephemerides = listOf(d, d)) }
        assertThrows(IllegalArgumentException::class.java) { d.copy(uraIndex = 15) }
        assertThrows(IllegalArgumentException::class.java) { d.copy(ephemeris = d.ephemeris.copy(health = 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            e.copy(clock = e.clock.copy(gpsSecondsOfWeek = e.clock.gpsSecondsOfWeek - 30))
        }
    }

    @Test fun belowHorizonOrMalformedStatusCannotAuthorizePhysicalGpsIdentity() {
        val c = capture()
        val hidden = c.copy(frames = c.frames.map { frame ->
            frame.copy(satellites = frame.satellites.map { it.copy(elevationDeg = -0.1f) })
        })
        assertNull(GpsBroadcastEvidence.fromCapture(hidden))
        assertThrows(IllegalArgumentException::class.java) {
            c.frames.single().satellites.single().copy(cn0DbHz = 63.1f)
        }
        val invalidAzimuth = c.copy(frames = c.frames.map { frame ->
            frame.copy(satellites = frame.satellites.map { it.copy(azimuthDeg = 360f) })
        })
        assertNull(GpsBroadcastEvidence.fromCapture(invalidAzimuth))
    }

    @Test fun wireDecodesRawOnceAndRetainsCompactEvidenceAcrossPeerSynchronization() {
        val c = capture()
        val e = GpsBroadcastEvidence.fromCapture(c)!!
        val compact = GnssCaptureWire.decode(GnssCaptureWire.encode(c))
        assertEquals(e, compact.gpsBroadcastEvidence)
        assertTrue(compact.measurements.isEmpty())
        assertTrue(compact.navigation.isEmpty())
        assertEquals(compact, GnssCaptureWire.decode(GnssCaptureWire.encode(compact)))
        assertEquals(compact, GnssCaptureCodec.decode(GnssCaptureCodec.encode(compact)))
        // New incomplete raw input must not rehabilitate old compact evidence.
        val replaced = compact.copy(navigation = c.navigation.take(2))
        assertNull(GnssCaptureWire.decode(GnssCaptureWire.encode(replaced)).gpsBroadcastEvidence)
        assertThrows(IllegalArgumentException::class.java) { compact.copy(bootCount = 43) }
        assertThrows(IllegalArgumentException::class.java) {
            compact.copy(frames = listOf(GnssObservationFrame(0, satellites = emptyList())))
        }
    }

    @Test fun compactEvidenceMustMeetTheSameVisibleStatusRequirementAsRawInput() {
        val c = capture()
        val compact = GnssCaptureWire.decode(GnssCaptureWire.encode(c))
        val observed = c.frames.single().satellites.single()
        for (invalid in listOf(observed.copy(elevationDeg = -0.1f), observed.copy(azimuthDeg = 360f),
            observed.copy(carrierHz = Float.NaN), observed.copy(carrierHz = 0f))) {
            val frames = listOf(c.frames.single().copy(satellites = listOf(invalid)))
            assertNull(GpsBroadcastEvidence.fromCapture(c.copy(frames = frames)))
            assertThrows(IllegalArgumentException::class.java) { compact.copy(frames = frames) }
        }
    }
}
