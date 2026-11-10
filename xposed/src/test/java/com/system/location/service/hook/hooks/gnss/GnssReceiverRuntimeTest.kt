package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.ProviderReadContext
import org.junit.Assert.*
import org.junit.Test

class GnssReceiverRuntimeTest {
    @Test fun capturedBroadcastEvidenceReachesSharedStatusNmeaAndExtrasPublication() {
        val now = System.currentTimeMillis() / 1000 * 1000
        val point = Wgs84(10.0, 20.0)
        val elapsed = 100_000_000_000L
        val toe = GpsTime(2400, 100000.0)
        // Synthetic circular-orbit integration fixture, never a production satellite catalog.
        val e = GpsLnavEphemeris(1, toe, toe, toe.plusSeconds(-7200.0), toe.plusSeconds(7200.0),
            1, 1, 1, 0, 5153.795, 0.0, 0.95,
            GpsBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND * toe.secondsOfWeek,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0)
        val capture = GnssCapture(deviceModel = "test", osVersion = "test", startedAtMillis = now - 61000,
            endedAtMillis = now - 1000, bootCount = 42,
            gpsBroadcastEvidence = GpsBroadcastEvidence(GpsElapsedClockEvidence(42, toe.week, toe.secondsOfWeek,
                elapsed, 1e-8, 1e-8), listOf(DecodedGpsLnav(e, 0, toe.plusSeconds(-30.0)))),
            frames = (0..60).map { index -> GnssObservationFrame(now - 61000 + index * 1000, point,
                satellites = listOf(GnssSatellite(Constellation.GPS, 1,
                    if (index % 2 == 0) 0f else 90f, 0f, 35f, true, true, true))) })
        try {
            GnssReceiverRuntime.configure(GnssCaptureWire.encode(capture))
            val sample = LocationSample(point, 25.0, 5f, 0f, 0f, now, elapsed)
            val publication = GnssReceiverRuntime.frameForSample(sample, currentBootCount = 42)!!
            assertEquals(ReceiverGnssSource.BROADCAST_EPHEMERIS, publication.frame.source)
            val satellite = publication.frame.satellites.single()
            val expected = GpsReceptionGeometry.observe(e, toe, point, 25.0).observation!!.lineOfSight
            assertEquals(expected.elevationDegrees, satellite.elevationDeg.toDouble(), 1e-5)
            assertNull(publication.frame.geometry.dop)
            assertFalse(satellite.usedInFix)
            val nmea = com.system.location.service.hook.hooks.nmea.NmeaBridge.generatedSentences(publication)
            val extras = GnssLocationExtras.from(publication)
            assertEquals(0, extras.satellites)
            val same = GnssReceiverRuntime.frameForSample(sample.copy(timeMillis = now + 500,
                elapsedNanos = elapsed + 500_000_000L), currentBootCount = 42)!!
            assertSame(publication, same)
            assertEquals(nmea, com.system.location.service.hook.hooks.nmea.NmeaBridge.generatedSentences(same))
            assertEquals(extras, GnssLocationExtras.from(same))
            val otherBoot = GnssReceiverRuntime.frameForSample(sample, currentBootCount = 43)!!
            assertEquals(ReceiverGnssSource.STATISTICAL_REPLAY, otherBoot.frame.source)
            assertNotEquals(publication.sequence, otherBoot.sequence)
        } finally { GnssReceiverRuntime.configure(null) }
    }

    @Test fun routeIdentitySurvivesMovementAndKeepsEachOutputOnTheSamePublication() {
        val now = System.currentTimeMillis() / 1000 * 1000
        val point = Wgs84(31.0, 121.0)
        val satellites = (0..4).map { index -> GnssSatellite(Constellation.GPS, index + 1,
            if (index == 4) 90f else 30f, (index % 4) * 90f, 35f, true, true, true) }
        val capture = GnssCapture(deviceModel = "receiver", osVersion = "os",
            startedAtMillis = now - 61000, endedAtMillis = now - 1000,
            frames = (0..60).map { GnssObservationFrame(now - 61000 + it * 1000, point, satellites = satellites) })
        try {
            GnssReceiverRuntime.configure(GnssCaptureWire.encode(capture))
            val sample = LocationSample(point, 25.0, 5f, 2f, 30f, now, 1000)
            val initial = GnssReceiverRuntime.frameForSample(sample)!!
            assertNotNull(initial.frame.geometry.dop)
            val sentences = com.system.location.service.hook.hooks.nmea.NmeaBridge.generatedSentences(initial)
            val extras = GnssLocationExtras.from(initial)
            repeat(9) { tick ->
                val moving = sample.copy(coordinate = Wgs84(31.0 + tick * 0.00001, 121.0),
                    altitude = 25.0 + tick, speed = 2f + tick, bearing = 30f + tick, timeMillis = now + tick * 100)
                val publication = GnssReceiverRuntime.frameForSample(moving)!!
                assertSame(initial, publication)
                assertEquals(sentences, com.system.location.service.hook.hooks.nmea.NmeaBridge.generatedSentences(publication))
                assertEquals(extras, GnssLocationExtras.from(publication))
            }
            listOf(GnssFrameRoute.FixedPoint(point), GnssFrameRoute.Scenario("route-a"),
                GnssFrameRoute.Scenario("route-b")).forEach { route ->
                val other = GnssReceiverRuntime.frameForSample(sample, route)!!
                assertNotEquals(initial.sequence, other.sequence)
                assertSame(other, GnssReceiverRuntime.frameForSample(sample.copy(timeMillis = now + 900), route))
            }
            assertNotEquals(initial.sequence, GnssReceiverRuntime.frameForSample(sample.copy(timeMillis = now + 1000))!!.sequence)
        } finally { GnssReceiverRuntime.configure(null) }
    }

    @Test fun missingCapturePassesThroughAndMalformedReplacementPreservesAcceptedCapture() {
        val now = System.currentTimeMillis() / 1000 * 1000
        val point = Wgs84(31.0, 121.0)
        val capture = GnssCapture(deviceModel = "receiver", osVersion = "os", startedAtMillis = now - 61000, endedAtMillis = now - 1000,
            frames = (0..60).map { index -> GnssObservationFrame(now - 61000 + index * 1000, point,
                satellites = listOf(GnssSatellite(Constellation.GPS, 1, 30f, 120f, 35f, true, true, true))) })
        val sample = com.system.location.service.core.location.LocationSample(point, 25.0, 5f, 2f, 30f, now, 1000)
        try {
            GnssReceiverRuntime.configure(null)
            assertNull(GnssReceiverRuntime.currentFrame(point))
            GnssReceiverRuntime.configure(GnssCaptureWire.encode(capture))
            assertNotNull(GnssReceiverRuntime.frameForSample(sample))
            val first = GnssReceiverRuntime.frameForSample(sample)!!
            val originalState = FakeLoc.runtimeSnapshots.current()
            val context = ProviderReadContext(originalState.copy(sample = sample), emptyMap(), now)
            assertSame(first, GnssReceiverRuntime.frameForSample(sample.copy(timeMillis = now + 100)))
            assertEquals(sample.coordinate, first.frame.observer)
            assertEquals(sample.altitude, first.frame.altitudeMeters, 0.0)
            assertEquals(2.0, first.speedMps, 0.0)
            assertEquals(30.0, first.bearingDeg, 0.0)
            assertThrows(IllegalArgumentException::class.java) { GnssReceiverRuntime.configure("!!") }
            assertNotNull(GnssReceiverRuntime.frameForSample(sample))
            GnssReceiverRuntime.configure(null)
            assertNull(GnssReceiverRuntime.currentFrame(point))
            assertSame(first, GnssReceiverRuntime.frameForSample(sample, state = originalState))
            assertSame(first, GnssReceiverRuntime.frameForRoute(GnssFrameRoute.Global, context))
        } finally { GnssReceiverRuntime.configure(null) }
    }
}
