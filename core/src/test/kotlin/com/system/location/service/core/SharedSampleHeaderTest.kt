package com.system.location.service.core

import com.system.location.service.core.runtime.SharedSampleHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedSampleHeaderTest {
    private val header = SharedSampleHeader(writeIndex = 1, sequence = 42, elapsedNanos = 123_456)

    @Test fun roundTripPreservesFields() {
        val bytes = header.encode()
        assertEquals(SharedSampleHeader.HEADER_BYTES, bytes.size)
        assertEquals(header, SharedSampleHeader.decode(bytes))
    }

    @Test fun crcMagicAndVersionAreChecked() {
        val corrupted = header.encode().also { it[10] = (it[10] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { SharedSampleHeader.decode(corrupted) }

        val wrongMagic = header.encode().also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) { SharedSampleHeader.decode(wrongMagic) }

        val wrongVersion = header.encode().also { it[4] = 2 }
        assertThrows(IllegalArgumentException::class.java) { SharedSampleHeader.decode(wrongVersion) }
    }

    @Test fun invalidFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedSampleHeader(writeIndex = 2, sequence = 1, elapsedNanos = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedSampleHeader(writeIndex = 0, sequence = 0, elapsedNanos = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedSampleHeader(writeIndex = 0, sequence = 1, elapsedNanos = -1)
        }
        assertThrows(IllegalArgumentException::class.java) { SharedSampleHeader.decode(ByteArray(4)) }
    }
}
