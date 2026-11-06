package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.SharedSampleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedSampleCodecTest {
    private val sample = LocationSample(
        Wgs84(31.2304, 121.4737), 80.5, 5.5f, 1.25f, 90f, 1_700_000_000_000L, 123_456L,
    )

    @Test fun roundTripPreservesFields() {
        val bytes = SharedSampleCodec.encode(sample)
        assertEquals(SharedSampleCodec.SLOT_BYTES, bytes.size)
        val decoded = SharedSampleCodec.decode(bytes)
        assertEquals(sample.latitude, decoded.latitude, 0.0)
        assertEquals(sample.longitude, decoded.longitude, 0.0)
        assertEquals(sample.altitude, decoded.altitude, 0.0)
        assertEquals(sample.accuracy, decoded.accuracy, 0f)
        assertEquals(sample.speed, decoded.speed, 0f)
        assertEquals(sample.bearing, decoded.bearing, 0f)
        assertEquals(sample.timeMillis, decoded.timeMillis)
        assertEquals(sample.elapsedNanos, decoded.elapsedNanos)
    }

    @Test fun crcAndMagicAreChecked() {
        val bytes = SharedSampleCodec.encode(sample)
        bytes[10] = (bytes[10] + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SharedSampleCodec.decode(bytes) }

        val wrongMagic = SharedSampleCodec.encode(sample)
        wrongMagic[0] = 0
        assertThrows(IllegalArgumentException::class.java) { SharedSampleCodec.decode(wrongMagic) }
    }

    @Test fun badSizeVersionAndCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SharedSampleCodec.decode(ByteArray(10)) }

        val wrongVersion = SharedSampleCodec.encode(sample)
        wrongVersion[4] = 2
        assertThrows(IllegalArgumentException::class.java) { SharedSampleCodec.decode(wrongVersion) }

        val invalid = LocationSample(
            Wgs84(0.0, 0.0), 80.0, 5f, 1f, 0f, 0L, 0L,
        )
        val bytes = SharedSampleCodec.encode(invalid)
        // Corrupt latitude and recompute nothing: CRC check fires first.
        bytes[12] = (bytes[12] + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SharedSampleCodec.decode(bytes) }
    }
}
