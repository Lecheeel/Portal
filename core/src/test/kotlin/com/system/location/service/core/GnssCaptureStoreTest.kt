package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GnssCaptureStoreTest {
    private fun capture() = GnssCapture(
        deviceModel = "device", osVersion = "os", startedAtMillis = 1, endedAtMillis = 2,
        frames = listOf(GnssObservationFrame(1, satellites = listOf(
            GnssSatellite(Constellation.GPS, 1, 25f, 123f, 32f, true, true, true)))),
        navigation = listOf(GnssNavigationObservation(1, 0x0101, 1, 1, 2, 3, "AQID")),
    )

    @Test fun roundTripRetainsReceiverEvidenceAndMetadata() {
        val expected = capture()
        val decoded = GnssCaptureCodec.decode(GnssCaptureCodec.encode(expected))
        assertEquals(expected, decoded)
        assertEquals(1, decoded.profile().totalSatelliteSamples)
    }

    @Test fun replacementIsAtomicAndDoesNotLeaveTemporaryFiles() {
        val directory = Files.createTempDirectory("gnss-store").toFile()
        try {
            val store = GnssCaptureStore(directory.resolve("capture.json"))
            assertNull(store.load())
            store.save(capture())
            store.save(capture().copy(deviceModel = "updated"))
            assertEquals("updated", store.load()!!.deviceModel)
            assertEquals(listOf("capture.json"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsUnsupportedVersionAndInvalidNavigationPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            GnssCaptureCodec.decode(GnssCaptureCodec.encode(capture()).replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GnssNavigationObservation(1, 1, 1, 1, 1, 1, "not base64!")
        }
    }
}
