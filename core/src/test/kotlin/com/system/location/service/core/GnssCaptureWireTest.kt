package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssCaptureWireTest {
    @Test fun wireRetainsStatusWithoutSendingRawNavigationEvidence() {
        val capture = GnssCapture(deviceModel = "device", osVersion = "os", startedAtMillis = 1000, endedAtMillis = 61000,
            frames = listOf(GnssObservationFrame(1000L, satellites = emptyList())),
            navigation = listOf(GnssNavigationObservation(1000, 0x101, 1, 1, 2, 3, "AQID")))
        val decoded = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
        assertEquals(capture.frames, decoded.frames)
        assertEquals(capture.deviceModel, decoded.deviceModel)
        assertTrue(decoded.navigation.isEmpty())
    }

    @Test fun rejectsOversizedOrMalformedPayloads() {
        assertThrows(IllegalArgumentException::class.java) { GnssCaptureWire.decode("x".repeat(GnssCaptureWire.MAX_ENCODED_LENGTH + 1)) }
        assertThrows(IllegalArgumentException::class.java) { GnssCaptureWire.decode("!!") }
    }
}
