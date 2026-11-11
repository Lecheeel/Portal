package com.system.location.service.core.gnss

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** App-owned atomic cache; readers keep their immutable resources across update failures. */
class RinexNavigationResourceStore(private val file: File) {
    @Serializable
    private data class Cache(val cacheVersion: Int = 1, val resource: RinexNavigationResource,
        val uploadHighWater: Map<String, Double>)

    private val json = Json { encodeDefaults = true }
    private val maximumCacheBytes = RinexNavigationResource.MAX_JSON_BYTES + 262144

    private fun uploads(resource: RinexNavigationResource): Map<String, Double> = resource.parsed.records
        .groupBy(RinexNavigationUploadOrder::identity).mapNotNull { (identity, records) ->
            records.mapNotNull(RinexNavigationUploadOrder::seconds).maxOrNull()?.let { identity to it }
        }.toMap()

    private fun readCache(): Cache? {
        if (!file.exists()) return null
        require(file.isFile && file.length() <= maximumCacheBytes) { "Invalid RINEX resource file" }
        val text = file.readText(Charsets.UTF_8)
        val cache = if ("cacheVersion" in json.parseToJsonElement(text).jsonObject)
            json.decodeFromString(Cache.serializer(), text)
        else RinexNavigationResource.decode(text).let { Cache(resource = it, uploadHighWater = uploads(it)) }
        require(cache.cacheVersion == 1)
        cache.resource.validate()
        require(cache.uploadHighWater.size <= 4096 && cache.uploadHighWater.all { (identity, epoch) ->
            identity.length <= 64 && identity.matches(Regex("[GECRJSI][0-9]{1,2}(?::(?:unknown|[-+0-9.Ee]+))?")) && epoch.isFinite()
        }) { "Invalid native upload high-water marks" }
        require(uploads(cache.resource).all { (identity, epoch) -> cache.uploadHighWater[identity]?.let { it >= epoch } == true })
        return cache
    }

    @Synchronized
    fun load(): RinexNavigationResource? = readCache()?.resource

    @Synchronized
    fun update(text: String): RinexNavigationResource {
        val incoming = RinexNavigationResource.decode(text)
        val previousCache = runCatching { readCache() }.getOrNull()
        val previous = previousCache?.resource
        // Metadata refresh is independent of broadcast uploads. An outage/omission or stale
        // HTTP copy cannot erase a newer accepted assignment catalog, including after restart.
        val oldCatalog = previous?.beiDouCatalog
        val newCatalog = incoming.beiDouCatalog
        val candidate = if (oldCatalog != null &&
            (newCatalog == null || newCatalog.creationLabel.isBefore(oldCatalog.creationLabel)))
            incoming.copy(beiDouCatalog = oldCatalog) else incoming
        // Verify the merged IPC fit before promotion; never truncate either data source.
        RinexNavigationResource.encodeWire(candidate)
        val previousEpochs = previous?.epochLabels().orEmpty()
        val nextEpochs = candidate.epochLabels()
        require(previousEpochs.all { (system, epoch) -> nextEpochs[system]?.let { it >= epoch } == true }) {
            "RINEX update would drop a system or roll back its native reference epoch"
        }
        val nextUploads = uploads(candidate)
        val present = candidate.parsed.records.map(RinexNavigationUploadOrder::identity).toSet()
        val highWater = previousCache?.uploadHighWater.orEmpty().toMutableMap()
        for ((identity, epoch) in highWater) if (identity in present) {
            require(nextUploads[identity]?.let { it >= epoch } == true) {
                "RINEX update would roll back or lose known native upload order for $identity"
            }
        }
        // Omission is not a reset: retain marks for identities missing from today's snapshot.
        // Reappearance after a process restart cannot revive an older upload as the newest one.
        highWater.putAll(nextUploads)
        require(highWater.size <= 4096) { "Too many RINEX upload identities" }
        val bytes = json.encodeToString(Cache.serializer(), Cache(resource = candidate, uploadHighWater = highWater))
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= maximumCacheBytes) { "RINEX cache exceeds size limit" }
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
