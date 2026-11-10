package com.system.location.service.core.gnss

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bounded Binder payload. Raw measurement/navigation evidence stays in the application's store. */
object GnssCaptureWire {
    const val MAX_ENCODED_LENGTH = 262_144

    fun encode(capture: GnssCapture): String {
        val statusOnly = capture.copy(measurements = emptyList(), navigation = emptyList())
        val data = GnssCaptureCodec.encode(statusOnly).toByteArray(Charsets.UTF_8)
        require(data.size <= GnssCaptureCodec.MAX_BYTES) { "GNSS status capture too large" }
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(data) }
        val encoded = Base64.getEncoder().encodeToString(compressed.toByteArray())
        require(encoded.length <= MAX_ENCODED_LENGTH) { "GNSS capture exceeds Binder budget" }
        return encoded
    }

    fun decode(encoded: String): GnssCapture {
        require(encoded.length <= MAX_ENCODED_LENGTH) { "GNSS capture exceeds Binder budget" }
        val compressed = Base64.getDecoder().decode(encoded)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= GnssCaptureCodec.MAX_BYTES) { "GNSS decompression limit exceeded" }
                output.write(chunk, 0, count)
            }
        }
        return GnssCaptureCodec.decode(output.toString(Charsets.UTF_8.name()))
    }
}
