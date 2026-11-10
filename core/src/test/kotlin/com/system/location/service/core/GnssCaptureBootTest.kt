package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssCaptureBootTest {
    private fun capture() = GnssCapture(deviceModel = "test receiver", osVersion = "test os",
        startedAtMillis = 0, endedAtMillis = 1, frames = listOf(GnssObservationFrame(0, satellites = emptyList())))

    @Test fun bootIdentitySurvivesLocalAndBoundedPeerFormatsWhileLegacyEvidenceStaysUnknown() {
        val capture = capture().copy(bootCount = 48)
        assertEquals(48, GnssCaptureCodec.decode(GnssCaptureCodec.encode(capture)).bootCount)
        assertEquals(48, GnssCaptureWire.decode(GnssCaptureWire.encode(capture)).bootCount)
        val legacy = GnssCaptureCodec.encode(capture()).replace(",\"bootCount\":null", "")
        assertNull(GnssCaptureCodec.decode(legacy).bootCount)
        assertThrows(IllegalArgumentException::class.java) { capture.copy(bootCount = -1) }
    }
}
