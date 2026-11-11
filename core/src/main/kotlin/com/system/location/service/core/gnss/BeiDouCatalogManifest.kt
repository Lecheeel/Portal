package com.system.location.service.core.gnss

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version and source-era evidence stored alongside the original SINEX subset. */
@Serializable
data class BeiDouCatalogManifest(
    val formatVersion: Int,
    val metadataSpecification: String,
    val fullCreationYear: Int,
    val sourceUrl: String,
    /** Attribution fingerprint of the full upstream file; the subset cannot reverify its body. */
    val originalSha256: String,
    /** SHA-256 of UTF-8 subset text with CRLF canonically converted to LF. */
    val subsetSha256: String,
) {
    init {
        require(formatVersion == 1 && metadataSpecification == "1.10" && fullCreationYear in 2006..9999)
        val uri = URI(sourceUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(sourceUrl.length <= 2048 && originalSha256.matches(Regex("[a-f0-9]{64}")) &&
            subsetSha256.matches(Regex("[a-f0-9]{64}")))
    }

    fun catalog(text: String, utc: GnssUtcTimeScale, maximumOpenIntervalAgeSeconds: Long): BeiDouSkyCatalog {
        require(text.length <= 2 * 1024 * 1024)
        val canonical = text.replace("\r\n", "\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(digest == subsetSha256) { "BeiDou metadata subset digest mismatch" }
        val metadata = BeiDouSatelliteMetadata.parse(canonical)
        return BeiDouSkyCatalog(metadata, fullCreationYear, maximumOpenIntervalAgeSeconds, utc,
            "$sourceUrl; SINEX $metadataSpecification; ${metadata.creationAt(fullCreationYear)}; SHA-256 $originalSha256")
    }

    companion object {
        fun decode(text: String): BeiDouCatalogManifest {
            require(text.length <= 4096)
            return Json.decodeFromString(serializer(), text)
        }
    }
}
