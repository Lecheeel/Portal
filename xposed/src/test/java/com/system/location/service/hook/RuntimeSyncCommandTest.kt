package com.system.location.service.hook

import com.system.location.service.core.runtime.RuntimeTransferWire
import org.junit.Assert.*
import org.junit.Test

class RuntimeSyncCommandTest {
    private val valid = mapOf<String, Any?>("proxy_session" to "session", "proxy_sequence" to 1L,
        "runtime_transfer" to byteArrayOf(1))

    @Test fun rejectsWrongTypesMissingFieldsAndOversizedMessages() {
        for ((key, badValues) in mapOf(
            "proxy_session" to listOf(null, "", "x".repeat(129), 1),
            "proxy_sequence" to listOf(null, 1, 0L, -1L, "1"),
            "runtime_transfer" to listOf(null, "encoded", byteArrayOf(),
                ByteArray(RuntimeTransferWire.MAX_COMPRESSED_BYTES + 1)),
        )) for (value in badValues) {
            assertThrows(Exception::class.java) { RuntimeSyncCommand.prepare(valid + (key to value)) }
        }
        for (key in valid.keys) {
            assertThrows(Exception::class.java) { RuntimeSyncCommand.prepare(valid - key) }
        }
        val message = RuntimeSyncCommand.prepare(valid)
        assertEquals("session", message.session)
        assertEquals(1L, message.sequence)
        assertArrayEquals(byteArrayOf(1), message.encoded)
    }
}
