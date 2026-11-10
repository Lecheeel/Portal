package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import org.junit.Assert.*
import org.junit.Test

class GnssReceiverRuntimeTest {
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
            assertSame(first, GnssReceiverRuntime.frameForSample(sample.copy(timeMillis = now + 100)))
            assertEquals(sample.coordinate, first.frame.observer)
            assertEquals(sample.altitude, first.frame.altitudeMeters, 0.0)
            assertEquals(2.0, first.speedMps, 0.0)
            assertEquals(30.0, first.bearingDeg, 0.0)
            assertThrows(IllegalArgumentException::class.java) { GnssReceiverRuntime.configure("!!") }
            assertNotNull(GnssReceiverRuntime.frameForSample(sample))
            GnssReceiverRuntime.configure(null)
            assertNull(GnssReceiverRuntime.currentFrame(point))
        } finally { GnssReceiverRuntime.configure(null) }
    }
}
