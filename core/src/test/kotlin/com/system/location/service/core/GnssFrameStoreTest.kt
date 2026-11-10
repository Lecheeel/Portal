package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssFrameStoreTest {
    private val point = Wgs84(31.0, 121.0)
    private fun capture() = GnssCapture(deviceModel = "device", osVersion = "os", startedAtMillis = 1000, endedAtMillis = 61000,
        frames = (0..60).map { index -> GnssObservationFrame(1000L + index * 1000, point,
            satellites = listOf(GnssSatellite(Constellation.GPS, 1, 30f, 100f, 35f, true, true, true))) })

    @Test fun outputsSharePublicationAndCoordinatesStayIsolated() {
        val store = GnssFrameStore()
        store.configure(capture())
        val first = store.current(62100, point, 10.0, 0, 2.0, 90.0)!!
        assertSame(first, store.current(62999, point, 10.0, 0, 2.0, 90.0))
        val other = store.current(62500, Wgs84(40.0, 116.0), 10.0, 0, 0.0, 0.0)!!
        assertNotEquals(first.sequence, other.sequence)
        assertEquals(Wgs84(40.0, 116.0), other.frame.observer)
        assertTrue(store.current(63000, point, 10.0, 0, 2.0, 90.0)!!.sequence > other.sequence)
    }

    @Test fun movingAndNoisySamplesShareRoutePublicationUntilNextReceiverSecond() {
        val store = GnssFrameStore().apply { configure(capture()) }
        val first = store.current(62100, point, 10.0, 0, 2.0, 90.0, route = GnssFrameRoute.Global)!!
        repeat(8) { tick ->
            assertSame(first, store.current(62200L + tick * 100, Wgs84(31.0 + tick * 0.00001, 121.0),
                10.0 + tick, 0, 2.0 + tick, 80.0 + tick, route = GnssFrameRoute.Global))
        }
        assertEquals(point, first.frame.observer)
        assertEquals(2.0, first.speedMps, 0.0)
        val nextPoint = Wgs84(31.001, 121.0)
        val next = store.current(63000, nextPoint, 12.0, 0, 3.0, 100.0, route = GnssFrameRoute.Global)!!
        assertTrue(next.sequence > first.sequence)
        assertEquals(nextPoint, next.frame.observer)
    }

    @Test fun coincidentFixedAndScenarioRoutesDoNotShareTheGlobalReceiver() {
        val store = GnssFrameStore().apply { configure(capture()) }
        val routes = listOf(GnssFrameRoute.Global, GnssFrameRoute.FixedPoint(point),
            GnssFrameRoute.Scenario("a"), GnssFrameRoute.Scenario("b"))
        val frames = routes.map { store.current(62100, point, 10.0, 0, 0.0, 0.0, route = it)!! }
        assertEquals(routes.size, frames.map { it.sequence }.distinct().size)
        routes.zip(frames).forEach { (route, frame) ->
            assertSame(frame, store.current(62900, Wgs84(32.0, 122.0), 15.0, 0, 1.0, 2.0, route = route))
        }
        val updatedMinimum = store.current(62900, point, 10.0, 2, 0.0, 0.0, route = routes.first())!!
        assertEquals(2, updatedMinimum.frame.requestedMinimumSatellites)
        assertNotEquals(frames.first().sequence, updatedMinimum.sequence)
        store.configure(capture(), seed = 1)
        assertNotEquals(updatedMinimum.sequence,
            store.current(62900, point, 10.0, 2, 0.0, 0.0, route = routes.first())!!.sequence)
    }

    @Test fun oldLocationTimestampCannotReviveExpiredCaptureOrCachedPublication() {
        val store = GnssFrameStore()
        store.configure(capture())
        assertNotNull(store.current(62000, point, 10.0, 0, 0.0, 0.0))
        assertNull(store.current(62000, point, 10.0, 0, 0.0, 0.0,
            validationAtMillis = 62000 + GnssCaptureValidator.MAX_AGE_MILLIS))
    }

    @Test fun noCaptureOrExpiredCaptureCannotProduceSyntheticFrame() {
        val store = GnssFrameStore()
        assertNull(store.current(62000, point, 10.0, 0, 0.0, 0.0))
        store.configure(capture())
        assertNotNull(store.current(62000, point, 10.0, 0, 0.0, 0.0))
        assertNull(store.current(62000 + GnssCaptureValidator.MAX_AGE_MILLIS, point, 10.0, 0, 0.0, 0.0))
        store.configure(null)
        assertNull(store.current(62000, point, 10.0, 0, 0.0, 0.0))
    }

}
