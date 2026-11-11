package com.system.location.service.core.gnss

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Download time is metadata, never the orbital reference epoch or an age reset. */
@Serializable
data class GpsAlmanacResource(
    val formatVersion: Int = 1,
    val sourceUrl: String,
    val sha256: String,
    val resolvedReferenceWeek: Int,
    val referenceEpochMillis: Long,
    val downloadedAtMillis: Long,
    val yuma: String,
) {
    init {
        require(formatVersion == 1 && sourceUrl.startsWith("https://") && sourceUrl.length <= 2048)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(resolvedReferenceWeek >= 0 && referenceEpochMillis >= 0 && downloadedAtMillis >= 0)
        require(yuma.toByteArray(Charsets.UTF_8).size <= GpsYumaParser.MAX_BYTES)
    }

    fun provider(utc: GnssUtcTimeScale, maximumEpochDistanceSeconds: Double): GpsAlmanacOrbitProvider {
        require(digest(yuma) == sha256) { "GPS resource digest mismatch" }
        val records = GpsYumaParser.parse(yuma, resolvedReferenceWeek)
        require(records.first().referenceTime.week == resolvedReferenceWeek)
        return GpsAlmanacOrbitProvider(records, GnssOrbitProvenance("gps-yuma", sha256, sourceUrl,
            referenceEpochMillis, GnssOrbitSourceKind.ALMANAC), utc, maximumEpochDistanceSeconds)
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        const val MAX_BYTES = 524_288

        fun importYuma(yuma: String, sourceUrl: String, approximateEpochMillis: Long,
            downloadedAtMillis: Long, utc: GnssUtcTimeScale): GpsAlmanacResource {
            val anchor = requireNotNull(utc.gpsAtUtcMillis(approximateEpochMillis).time) { "Unknown UTC week anchor" }
            val records = GpsYumaParser.parse(yuma, anchor.week)
            val reference = records.first().referenceTime
            val referenceUtc = requireNotNull(utc.utcAtGpsTime(reference).utcMillis) { "Unknown almanac UTC epoch" }
            return GpsAlmanacResource(sourceUrl = sourceUrl, sha256 = digest(yuma),
                resolvedReferenceWeek = reference.week, referenceEpochMillis = referenceUtc,
                downloadedAtMillis = downloadedAtMillis, yuma = yuma)
        }

        fun decode(text: String): GpsAlmanacResource {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "GPS resource too large" }
            return json.decodeFromString(serializer(), text)
        }

        fun encode(resource: GpsAlmanacResource): String = json.encodeToString(serializer(), resource).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "GPS resource too large" }
        }

        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
