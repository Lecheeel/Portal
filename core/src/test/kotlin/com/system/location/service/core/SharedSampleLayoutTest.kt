package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.SharedSampleCodec
import com.system.location.service.core.runtime.SharedSampleHeader
import com.system.location.service.core.runtime.SharedSampleLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedSampleLayoutTest {
    private val sample = LocationSample(Wgs84(31.0, 121.0), 80.0, 5f, 1f, 90f, 100L, 200L)

    @Test fun writesHeaderAndSlotContiguously() {
        val buffer = ByteArray(SharedSampleLayout.SIZE)
        val header = SharedSampleLayout.write(buffer, sample, sequence = 3, elapsedNanos = 200L)
        assertEquals(1, header.writeIndex)
        assertEquals(SharedSampleHeader.HEADER_BYTES + 2 * SharedSampleCodec.SLOT_BYTES, SharedSampleLayout.SIZE)
        val (decodedHeader, decodedSample) = SharedSampleLayout.read(buffer)!!
        assertEquals(header, decodedHeader)
        assertEquals(sample.latitude, decodedSample.latitude, 0.0)
        assertEquals(sample.elapsedNanos, decodedSample.elapsedNanos)
    }

    @Test fun rejectsWrongSizeAndCorruption() {
        assertNull(SharedSampleLayout.read(ByteArray(10)))

        val buffer = ByteArray(SharedSampleLayout.SIZE)
        SharedSampleLayout.write(buffer, sample, sequence = 2, elapsedNanos = 1L)
        buffer[SharedSampleHeader.HEADER_BYTES + 10] = (buffer[SharedSampleHeader.HEADER_BYTES + 10] + 1).toByte()
        assertNull(SharedSampleLayout.read(buffer))
    }
}
