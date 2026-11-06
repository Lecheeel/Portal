package com.system.location.service.core.environment

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Importable WiFi fingerprint catalog (P2-06).
 *
 * Disabled by default; enabling requires non-blank source/license/attribution so a third-party
 * dump cannot be activated silently. The wire schema is versioned and unknown keys are ignored.
 */
@Serializable
data class WifiFingerprintCatalog(
    val schemaVersion: Int = SCHEMA_VERSION,
    val enabled: Boolean = false,
    val source: String = "",
    val license: String = "",
    val attribution: String = "",
    val entries: List<WifiFingerprint> = emptyList(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported fingerprint schema: $schemaVersion" }
        require(entries.size <= WifiFingerprintDb.MAX_ENTRIES) { "Too many fingerprints: ${entries.size}" }
        if (enabled) {
            require(source.isNotBlank()) { "source is required when enabled" }
            require(license.isNotBlank()) { "license is required when enabled" }
            require(attribution.isNotBlank()) { "attribution is required when enabled" }
        }
    }

    fun database(): WifiFingerprintDb = WifiFingerprintDb(entries)

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class WifiFingerprintImportResult(
    val catalog: WifiFingerprintCatalog,
    val imported: Int,
    val skipped: Int,
)

class WifiFingerprintImportException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object WifiFingerprintImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun importJson(raw: String): WifiFingerprintImportResult {
        val catalog = runCatching { json.decodeFromString(WifiFingerprintCatalog.serializer(), raw) }
            .getOrElse { throw WifiFingerprintImportException("Invalid fingerprint JSON", it) }
        return WifiFingerprintImportResult(catalog, catalog.entries.size, 0)
    }

    fun encodeJson(catalog: WifiFingerprintCatalog): String =
        json.encodeToString(WifiFingerprintCatalog.serializer(), catalog)

    /**
     * CSV columns: bssid,latitude,longitude,rssi,observedAtMillis. An optional header line is
     * accepted. Invalid rows are skipped and counted; an all-invalid file is rejected.
     */
    fun importCsv(
        raw: String,
        source: String,
        license: String,
        attribution: String,
    ): WifiFingerprintImportResult {
        require(source.isNotBlank() && license.isNotBlank() && attribution.isNotBlank()) {
            "source/license/attribution are required"
        }
        val entries = mutableListOf<WifiFingerprint>()
        var skipped = 0
        raw.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@forEachIndexed
            if (index == 0 && trimmed.startsWith("bssid,", ignoreCase = true)) return@forEachIndexed
            val parts = trimmed.split(',').map { it.trim() }
            if (parts.size != 5) {
                skipped++
                return@forEachIndexed
            }
            val fingerprint = runCatching {
                WifiFingerprint(
                    bssid = parts[0],
                    position = com.system.location.service.core.geo.Wgs84(
                        parts[1].toDouble(),
                        parts[2].toDouble(),
                    ),
                    rssi = parts[3].toInt(),
                    observedAtMillis = parts[4].toLong(),
                )
            }.getOrNull()
            if (fingerprint == null) {
                skipped++
            } else if (entries.size < WifiFingerprintDb.MAX_ENTRIES) {
                entries += fingerprint
            } else {
                skipped++
            }
        }
        if (entries.isEmpty()) throw WifiFingerprintImportException("No valid fingerprint rows")
        val catalog = WifiFingerprintCatalog(
            enabled = false,
            source = source,
            license = license,
            attribution = attribution,
            entries = entries,
        )
        return WifiFingerprintImportResult(catalog, entries.size, skipped)
    }
}
