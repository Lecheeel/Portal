package com.system.location.service.core

import com.system.location.service.core.gnss.*
import com.system.location.service.core.geo.Wgs84
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

    @Test fun ineligibleEvidenceCannotReplaceThePlaybackSource() {
        val directory = Files.createTempDirectory("gnss-promotion").toFile()
        try {
            val file = directory.resolve("capture.json")
            val store = GnssCaptureStore(file)
            val valid = capture().copy(endedAtMillis = 60_001,
                frames = (0..60).map { index -> capture().frames.single().copy(
                    capturedAtMillis = 1 + index * 1000L, observer = Wgs84(31.0, 121.0)) })
            store.saveForPlayback(valid, 60_001)
            val originalBytes = file.readBytes()
            for (invalid in listOf(capture(), valid.copy(frames = valid.frames.map { it.copy(observer = null) }))) {
                assertThrows(IllegalArgumentException::class.java) { store.saveForPlayback(invalid, 60_001) }
                assertArrayEquals(originalBytes, file.readBytes())
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.saveForPlayback(valid, valid.endedAtMillis + GnssCaptureValidator.MAX_AGE_MILLIS + 1)
            }
            assertArrayEquals(originalBytes, file.readBytes())
            // Incomplete evidence can still be archived, independently of the production source.
            val archive = GnssCaptureStore(directory.resolve("rejected.json"))
            archive.save(capture())
            assertEquals(capture(), archive.load())
            assertEquals(valid, store.load())
        } finally { directory.deleteRecursively() }
    }
}
