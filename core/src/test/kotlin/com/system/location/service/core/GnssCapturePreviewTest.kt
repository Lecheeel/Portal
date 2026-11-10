package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssCapturePreviewTest {
    private val point = Wgs84(31.0, 121.0)
    private val capture = GnssCapture(deviceModel = "receiver", osVersion = "android",
        startedAtMillis = 1000, endedAtMillis = 61000, frames = (0..60).map { index ->
            GnssObservationFrame(1000L + index * 1000, point, 10.0,
                listOf(GnssSatellite(Constellation.GPS, 1, 35f, 10f, 32f, true, true, true)))
        })

    @Test fun absentAndInvalidCaptureNeverShowInventedCapacity() {
        val waiting = GnssCapturePreviewer(null).current(62000, 20)
        assertEquals(GnssCapturePlaybackState.WAITING_FOR_CAPTURE, waiting.state)
        assertNull(waiting.frame)
        val invalid = GnssCapturePreviewer(capture.copy(frames = capture.frames.take(1))).current(62000, 20)
        assertEquals(GnssCapturePlaybackState.INVALID_CAPTURE, invalid.state)
        assertNull(invalid.frame)
        assertTrue(invalid.issues.isNotEmpty())
    }

    @Test fun previewMatchesProductionCapacityAndExpiresWithoutReloading() {
        val previewer = GnssCapturePreviewer(capture)
        val preview = previewer.current(62000, 20)
        assertEquals(GnssCapturePlaybackState.READY, preview.state)
        assertEquals(ReceiverGnssSkyModel(capture).frame(62000, point, 10.0, 20), preview.frame)
        assertEquals(1, preview.frame!!.availableSatelliteCapacity)
        assertTrue(preview.frame.diagnostics.any { it.contains("无法满足") })
        val expired = previewer.current(62000 + GnssCaptureValidator.MAX_AGE_MILLIS, 20)
        assertEquals(GnssCapturePlaybackState.INVALID_CAPTURE, expired.state)
        assertNull(expired.frame)
    }
}
