package com.system.location.service.core.gnss

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Validate before atomic promotion. Readers hold immutable providers; failed updates leave
 * the previous on-disk resource and any already published provider untouched.
 */
class GpsAlmanacResourceStore(private val file: File, private val utc: GnssUtcTimeScale) {
    @Synchronized
    fun load(): GpsAlmanacResource? {
        if (!file.exists()) return null
        require(file.isFile && file.length() <= GpsAlmanacResource.MAX_BYTES) { "Invalid GPS resource file" }
        return validated(file.readText(Charsets.UTF_8))
    }

    /** Import the complete candidate before touching disk, including its digest and epoch. */
    @Synchronized
    fun update(text: String): GpsAlmanacResource {
        val candidate = validated(text)
        // A delayed download must not roll a valid cache backwards. Same-epoch uploads may
        // change health and are allowed; download timestamps do not establish orbit freshness.
        val previous = runCatching { load() }.getOrNull()
        require(previous == null || candidate.referenceEpochMillis >= previous.referenceEpochMillis) {
            "GPS resource would roll back the cached orbital epoch"
        }
        val bytes = GpsAlmanacResource.encode(candidate).toByteArray(Charsets.UTF_8)
        val parent = requireNotNull(file.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create GPS resource directory" }
        val temporary = File.createTempFile("gps-orbit-", ".tmp", parent)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        return candidate
    }

    private fun validated(text: String): GpsAlmanacResource = GpsAlmanacResource.decode(text).also {
        // This checks completeness and metadata without asserting usefulness at today's epoch.
        // Zero age budget permits the resource's own reference epoch; per-use policy is separate.
        it.provider(utc, maximumEpochDistanceSeconds = 0.0)
    }
}
