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
