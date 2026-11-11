package com.system.location.service.core.gnss

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/** Compact, verifiable subset; neither download time nor HTTP date replaces the SINEX epoch. */
@Serializable
data class BeiDouCatalogResource(val manifest: BeiDouCatalogManifest, val subset: String) {
    init { require(subset.length <= 65536) { "BeiDou catalog subset exceeds IPC budget" } }

    val creationLabel: java.time.LocalDateTime get() = metadata.creationAt(manifest.fullCreationYear)
    private val metadata by lazy {
        require(digest(subset.replace("\r\n", "\n")) == manifest.subsetSha256) { "BeiDou catalog digest mismatch" }
        BeiDouSatelliteMetadata.parse(subset).also { it.creationAt(manifest.fullCreationYear) }
    }

    fun validate(): BeiDouCatalogResource = apply { metadata }
    fun catalog(utc: GnssUtcTimeScale, maximumAgeSeconds: Long): BeiDouSkyCatalog =
        manifest.catalog(subset, utc, maximumAgeSeconds)

    companion object {
        const val SOURCE_URL = "https://files.igs.org/pub/station/general/igs_satellite_metadata.snx"

        /** Last-Modified supplies independent full-year evidence, including December/January. */
        fun import(text: String, lastModifiedMillis: Long): BeiDouCatalogResource {
            require(lastModifiedMillis > 0)
            val original = BeiDouSatelliteMetadata.parse(text)
            val modified = Instant.ofEpochMilli(lastModifiedMillis).atOffset(ZoneOffset.UTC).toLocalDateTime()
            val year = (modified.year - 1..modified.year + 1).mapNotNull { year ->
                runCatching { original.creationAt(year) }.getOrNull()
            }.singleOrNull { creation ->
                val distance = Duration.between(creation, modified).seconds
                // Allow a producer's unspecified epoch scale and a small publication clock skew.
                distance in -86400L..366 * 86400L
            }?.year ?: error("Missing unambiguous SINEX source-era evidence")
            val lines = mutableListOf(text.lineSequence().first())
            var block: String? = null
            for (raw in text.lineSequence().drop(1)) {
                val line = raw.trimEnd()
                when {
                    line in listOf("+SATELLITE/IDENTIFIER", "+SATELLITE/PRN") -> { block = line.drop(1); lines += line }
                    block != null && line == "-$block" -> { lines += line; block = null }
                    block != null && line.startsWith(" C") -> lines += line
                }
            }
            lines += "%ENDSNX"
            val subset = lines.joinToString("\n", postfix = "\n")
            return BeiDouCatalogResource(BeiDouCatalogManifest(1, "1.10", year, SOURCE_URL,
                digest(text), digest(subset)), subset).validate()
        }

        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
