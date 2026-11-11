package com.system.location.service.core.gnss

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-owned atomic cache; readers keep their immutable resources across update failures. */
class RinexNavigationResourceStore(private val file: File) {
    @Synchronized
    fun load(): RinexNavigationResource? {
        if (!file.exists()) return null
        require(file.isFile && file.length() <= RinexNavigationResource.MAX_JSON_BYTES) { "Invalid RINEX resource file" }
        return RinexNavigationResource.decode(file.readText(Charsets.UTF_8))
    }

    @Synchronized
    fun update(text: String): RinexNavigationResource {
        val candidate = RinexNavigationResource.decode(text)
        // Verify IPC fit before promotion. An unpublishable update must not displace a usable cache.
        RinexNavigationResource.encodeWire(candidate)
        val previous = runCatching { load() }.getOrNull()
        val previousEpochs = previous?.epochLabels().orEmpty()
        val nextEpochs = candidate.epochLabels()
        require(previousEpochs.all { (system, epoch) -> nextEpochs[system]?.let { it >= epoch } == true }) {
            "RINEX update would drop a system or roll back its native reference epoch"
        }
        val bytes = RinexNavigationResource.encode(candidate).toByteArray(Charsets.UTF_8)
        val parent = requireNotNull(file.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create RINEX resource directory" }
        val temporary = File.createTempFile("rinex-orbit-", ".tmp", parent)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return candidate
    }
}
