package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssReceiverRuntimeTest {
    @Test fun missingCapturePassesThroughAndMalformedReplacementPreservesAcceptedCapture() {
        val now = System.currentTimeMillis() / 1000 * 1000
        val point = Wgs84(31.0, 121.0)
        val capture = GnssCapture(deviceModel = "receiver", osVersion = "os", startedAtMillis = now - 61000, endedAtMillis = now - 1000,
            frames = (0..60).map { index -> GnssObservationFrame(now - 61000 + index * 1000, point,
                satellites = listOf(GnssSatellite(Constellation.GPS, 1, 30f, 120f, 35f, true, true, true))) })
        try {
            GnssReceiverRuntime.configure(null)
            assertNull(GnssReceiverRuntime.currentFrame(point))
            GnssReceiverRuntime.configure(GnssCaptureWire.encode(capture))
            assertNotNull(GnssReceiverRuntime.currentFrame(point))
            assertThrows(IllegalArgumentException::class.java) { GnssReceiverRuntime.configure("!!") }
            assertNotNull(GnssReceiverRuntime.currentFrame(point))
            GnssReceiverRuntime.configure(null)
            assertNull(GnssReceiverRuntime.currentFrame(point))
        } finally { GnssReceiverRuntime.configure(null) }
    }
}
