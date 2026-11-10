package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverBroadcastSkyModelTest {
    private val point = Wgs84(89.999, -179.9)
    private val elapsed = 100_000_000_000L
    private fun decoded(): DecodedGpsLnav {
        val words = Json.parseToJsonElement(javaClass.getResource("/gnss/gps-lnav-words-reference.json")!!
            .readText()).jsonObject.getValue("subframes").jsonArray.take(3)
        val frames = words.map { value ->
            val f = value.jsonObject
            val id = f.getValue("id").jsonPrimitive.int
            val bytes = f.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            GpsLnavWords.decode(GnssNavigationObservation(id * 6000L, 0x0101, 1, 1, -1, id,
                Base64.getEncoder().encodeToString(bytes))).subframe!!
        }
        return GpsLnavEphemerisDecoder.decode(frames, GpsTime(1316, frames.first().nextTowSeconds.toDouble())).decoded!!
    }

    private fun capture(): GnssCapture {
        val d = decoded()
        return GnssCapture(deviceModel = "synthetic test receiver", osVersion = "test", startedAtMillis = 1000,
            endedAtMillis = 61000, bootCount = 42,
            gpsBroadcastEvidence = GpsBroadcastEvidence(GpsElapsedClockEvidence(42, d.ephemeris.toe.week,
                d.ephemeris.toe.secondsOfWeek, elapsed, 1e-8, 1e-8), listOf(d)),
            frames = (0..60).map { index -> GnssObservationFrame(1000L + index * 1000, point,
                satellites = buildList {
                    // GPS 1 is genuine evidence in the capture, but outside the local replay pool.
                    if (index == 0) add(GnssSatellite(Constellation.GPS, 1, 10f, 10f, 20f, true, true, true))
                    add(GnssSatellite(Constellation.GPS, 9, if (index % 2 == 0) 0f else 90f,
                        90f, if (index % 2 == 0) 20f else 50f, true, true, true))
                    repeat(3) { add(GnssSatellite(Constellation.BEIDOU, it + 1, 30f, it * 120f, 35f, true, true, true)) }
                }) })
    }

    @Test fun broadcastGeometryChangesWithObserverAndRecombinesCandidateDop() {
        val c = capture()
        val m = ReceiverBroadcastSkyModel(c)
        assertNull(ReceiverGnssSkyModel(c).frame(62000, point, 0.0)!!.geometry.dop)
        val result = m.frame(62000, point, 0.0, elapsedRealtimeNanos = elapsed, currentBootCount = 42)!!
        assertEquals(ReceiverGnssSource.HYBRID_BROADCAST_REPLAY, result.source)
        val gps = result.satellites.single { it.constellation == Constellation.GPS && it.svid == 1 }
        val expected = GpsReceptionGeometry.observe(decoded().ephemeris, decoded().ephemeris.toe, point, 0.0).observation!!
        assertEquals(expected.lineOfSight.elevationDegrees, gps.elevationDeg.toDouble(), 1e-5)
        assertEquals(expected.lineOfSight.azimuthDegrees!!, gps.azimuthDeg.toDouble(), 1e-5)
        assertNotEquals(10f, gps.elevationDeg)
        assertNotNull(result.geometry.dop)
        assertEquals(result.geometry.dop, GnssGeometry.solve(result.satellites).dop)
        assertEquals(result, m.frame(62000, point, 0.0, elapsedRealtimeNanos = elapsed, currentBootCount = 42))
        val hidden = m.frame(62000, Wgs84(0.0, 0.0), 0.0, 128, elapsed, 42)!!
        assertFalse(hidden.satellites.any { it.constellation == Constellation.GPS && it.svid == 1 })
        assertEquals(4, hidden.availableSatelliteCapacity)
        assertTrue(hidden.diagnostics.any { it.contains("无法满足") })
        assertNull(hidden.geometry.dop)
        assertTrue(hidden.satellites.none { it.usedInFix })
    }

    @Test fun oldBootMissingTimeAndExpiredClockUseExplicitStatisticalSource() {
        val m = ReceiverBroadcastSkyModel(capture())
        listOf(null to 42, elapsed to null, elapsed to 43, elapsed + 1801_000_000_000L to 42).forEach { (t, boot) ->
            val result = m.frame(62000, point, 0.0, elapsedRealtimeNanos = t, currentBootCount = boot)!!
            assertEquals(ReceiverGnssSource.STATISTICAL_REPLAY, result.source)
            assertTrue(result.diagnostics.any { it.contains("同次开机") })
        }
    }

    @Test fun capturePreviewUsesTheProductionModelAndExposesItsSource() {
        val c = capture()
        val previewer = GnssCapturePreviewer(c)
        val preview = previewer.current(62000, 0, elapsed, 42)
        assertEquals(GnssCapturePlaybackState.READY, preview.state)
        assertEquals(ReceiverBroadcastSkyModel(c).frame(62000, point, 0.0, 0, elapsed, 42), preview.frame)
        assertEquals(ReceiverGnssSource.HYBRID_BROADCAST_REPLAY, preview.frame!!.source)
        assertEquals(ReceiverGnssSource.STATISTICAL_REPLAY, previewer.current(62000, 0).frame!!.source)
    }

    @Test fun fitAndCalibrationFailuresCannotResurrectStatisticalGeometryForSameIdentity() {
        val c = capture()
        val e = c.gpsBroadcastEvidence!!
        val old = e.ephemerides.single()
        val shortFit = old.copy(ephemeris = old.ephemeris.copy(validUntil = old.ephemeris.toe.plusSeconds(1.0)))
        val invalidFit = c.copy(gpsBroadcastEvidence = e.copy(ephemerides = listOf(shortFit)))
        val after = ReceiverBroadcastSkyModel(invalidFit).frame(62000, point, 0.0, 128, elapsed + 2_000_000_000, 42)!!
        assertFalse(after.satellites.any { it.constellation == Constellation.GPS && it.svid == 1 })
        assertTrue(after.diagnostics.any { it.contains("fit interval") })
        val noCalibration = c.copy(frames = c.frames.map { f -> f.copy(satellites = f.satellites.map {
            if (it.constellation == Constellation.GPS) it.copy(elevationDeg = 5f) else it
        }) })
        val excluded = ReceiverBroadcastSkyModel(noCalibration).frame(62000, point, 0.0, 128, elapsed, 42)!!
        assertFalse(excluded.satellites.any { it.constellation == Constellation.GPS && it.svid == 1 })
        assertTrue(excluded.diagnostics.any { it.contains("未外推 CN0") })
    }

    @Test fun productionSourceSharesOneFrameButNeverReusesItAcrossBootOrElapsedSecond() {
        val source = CapturedGnssSource.prepare(GnssCaptureWire.encode(capture()))!!
        assertTrue(source.requiresBootIdentity)
        val sample = LocationSample(point, 0.0, 5f, 0f, 0f, 62000, elapsed)
        val first = source.frame(sample, 0, GnssFrameRoute.Global, 62000, 42)!!
        assertEquals(ReceiverGnssSource.HYBRID_BROADCAST_REPLAY, first.frame.source)
        assertSame(first, source.frame(sample.copy(timeMillis = 62900, elapsedNanos = elapsed + 900_000_000L),
            0, GnssFrameRoute.Global, 62900, 42))
        // Crossing UTC's boundary inside one elapsed second must not publish an extra frame.
        assertSame(first, source.frame(sample.copy(timeMillis = 63000, elapsedNanos = elapsed + 950_000_000L),
            0, GnssFrameRoute.Global, 63000, 42))
        val next = source.frame(sample.copy(elapsedNanos = elapsed + 1_000_000_000L),
            0, GnssFrameRoute.Global, 62000, 42)!!
        assertNotEquals(first.sequence, next.sequence)
        assertNotEquals(first.frame.satellites, next.frame.satellites)
        val otherBoot = source.frame(sample, 0, GnssFrameRoute.Global, 62000, 43)!!
        assertNotEquals(first.sequence, otherBoot.sequence)
        assertEquals(ReceiverGnssSource.STATISTICAL_REPLAY, otherBoot.frame.source)
        val missingBoot = source.frame(sample, 0, GnssFrameRoute.Global, 62000)!!
        assertEquals(ReceiverGnssSource.STATISTICAL_REPLAY, missingBoot.frame.source)
        // A frozen Location UTC timestamp cannot retain physical output indefinitely.
        val expired = source.frame(sample.copy(elapsedNanos = elapsed + 1801_000_000_000L),
            0, GnssFrameRoute.Global, 62000, 42)!!
        assertEquals(ReceiverGnssSource.STATISTICAL_REPLAY, expired.frame.source)
        assertNotEquals(first.sequence, expired.sequence)
        val fixed = source.frame(sample, 0, GnssFrameRoute.FixedPoint(point), 62000, 42)!!
        assertNotEquals(first.sequence, fixed.sequence)
        assertSame(first, source.frame(sample, 0, GnssFrameRoute.Global, 62000, 42))
    }

    @Test fun physicalFrameRetainsTheExactFirstSampleUtcLabelForItsElapsedEpoch() {
        val source = CapturedGnssSource.prepare(GnssCaptureWire.encode(capture()))!!
        val sample = LocationSample(point, 0.0, 5f, 0f, 0f, 62125, elapsed + 125_000_000L)
        val publication = source.frame(sample, 0, GnssFrameRoute.Global, 62125, 42)!!
        assertEquals(62125L, publication.frame.generatedAtMillis)
        val expected = GpsReceptionGeometry.observe(decoded().ephemeris,
            decoded().ephemeris.toe.plusSeconds(0.125), point, 0.0).observation!!.lineOfSight
        val gps = publication.frame.satellites.single { it.constellation == Constellation.GPS && it.svid == 1 }
        assertEquals(expected.elevationDegrees, gps.elevationDeg.toDouble(), 1e-5)
        val same = source.frame(sample.copy(timeMillis = 62800, elapsedNanos = elapsed + 800_000_000L),
            0, GnssFrameRoute.Global, 62800, 42)!!
        assertSame(publication, same)
        assertEquals(62125L, same.frame.generatedAtMillis)
    }
}
