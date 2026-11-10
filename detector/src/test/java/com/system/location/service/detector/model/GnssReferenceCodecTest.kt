package com.system.location.service.detector.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class GnssReferenceCodecTest {
    private fun reference(): GnssReference {
        val s = GnssSatelliteSnapshot(1, 3, 42f, 30f, 359f, true, false, true, true)
        return GnssReference("device", "室外，同一地点，模拟已关闭", 40_000,
            GnssEvidenceSnapshot(0, 30_000, (0L..30L).map { GnssFrameSnapshot(it * 1000, listOf(s)) }))
    }
    private fun encode(reference: GnssReference): ByteArray = ByteArrayOutputStream().also {
        GnssReferenceCodec.write(reference, it)
    }.toByteArray()
    private fun decode(bytes: ByteArray) = GnssReferenceCodec.read(ByteArrayInputStream(bytes))

    @Test fun roundTripPreservesRawSignalsMetadataAndStatistics() {
        val reference = reference()
        val result = decode(encode(reference))
        assertEquals(reference, result)
        assertEquals(reference.profile(), result.profile())
    }

    @Test fun partialVersionMismatchTrailingDataAndInvalidSourceAreRejected() {
        val bytes = encode(reference())
        listOf(bytes.copyOf(7), bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0),
            bytes.clone().also { it[7] = 2 }).forEach {
            assertThrows(Exception::class.java) { decode(it) }
        }
        listOf(reference().copy(environment = ""), reference().copy(savedAtMillis = 0),
            reference().copy(evidence = reference().evidence.copy(frames = emptyList()))).forEach {
            assertThrows(IllegalArgumentException::class.java) { encode(it) }
        }
    }

    @Test fun untrustedFrameCountCannotAllocateAnUnboundedList() {
        val bytes = encode(reference())
        val header = ByteArrayInputStream(bytes)
        val input = java.io.DataInputStream(header)
        input.readInt(); input.readInt(); input.readUTF(); input.readUTF()
        input.readLong(); input.readLong(); input.readLong()
        val countOffset = bytes.size - header.available()
        for (i in 0..3) bytes[countOffset + i] = 127
        assertThrows(IllegalArgumentException::class.java) { decode(bytes) }
    }
}
