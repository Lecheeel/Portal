package com.system.location.service.core.gnss

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

object GnssCaptureCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(capture: GnssCapture): String = json.encodeToString(GnssCapture.serializer(), capture)

    fun decode(raw: String): GnssCapture {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "GNSS capture too large" }
        return json.decodeFromString(GnssCapture.serializer(), raw)
    }
}

/** Keep the old capture intact on any failure; callers perform disk I/O off the UI thread. */
class GnssCaptureStore(private val file: File) {
    fun load(): GnssCapture? {
        if (!file.isFile) return null
        require(file.length() <= GnssCaptureCodec.MAX_BYTES) { "GNSS capture too large" }
        return GnssCaptureCodec.decode(file.readText(Charsets.UTF_8))
    }

    @Synchronized
    fun save(capture: GnssCapture) {
        val bytes = GnssCaptureCodec.encode(capture).toByteArray(Charsets.UTF_8)
        require(bytes.size <= GnssCaptureCodec.MAX_BYTES) { "GNSS capture too large" }
        val parent = file.absoluteFile.parentFile
        require(parent.isDirectory || parent.mkdirs()) { "cannot create GNSS capture directory" }
        val temporary = File.createTempFile("gnss-capture-", ".tmp", parent)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
