package com.system.location.service.core.gnss

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned original navigation text. Download time never becomes an orbital reference epoch.
 * Transport is smaller than an entire daily archive; archive selection must happen before import
 * and must preserve native epochs, transmissions and applicable negative health evidence.
 */
@Serializable
data class RinexNavigationResource(
    val formatVersion: Int = 1,
    val sourceUrl: String,
    val sha256: String,
    val downloadedAtMillis: Long,
    val navigation: String,
) {
    init {
        require(formatVersion == 1 && sourceUrl.length <= 2048)
        val source = URI(sourceUrl)
        require(source.scheme == "https" && !source.host.isNullOrBlank() && source.userInfo == null && source.fragment == null)
        require(sha256.matches(Regex("[a-f0-9]{64}")) && downloadedAtMillis >= 0)
        require(navigation.length <= MAX_NAVIGATION_BYTES && navigation.toByteArray(Charsets.UTF_8).size <= MAX_NAVIGATION_BYTES)
    }

    // Process-local cache, excluded from serialization/equality. No receiver or frame state here.
    internal val parsed: RinexNavigationFile by lazy {
        require(digest(navigation) == sha256) { "RINEX resource digest mismatch" }
        RinexNavigationReader.read(navigation)
    }

    fun validate(): RinexNavigationResource = apply { parsed }

    fun gpsProvider(utc: GnssUtcTimeScale): RinexGpsOrbitProvider {
        val gps = parsed.records.filter { it.system == RinexNavigationSystem.GPS }
            .mapNotNull { runCatching { RinexGpsImporter.decode(it) }.getOrNull() }
        require(gps.isNotEmpty()) { "RINEX resource has no usable GPS broadcast records" }
        val reference = gps.maxBy { it.ephemeris.toe.week * 604800.0 + it.ephemeris.toe.secondsOfWeek }.ephemeris.toe
        val epoch = requireNotNull(utc.utcAtGpsTime(reference).utcMillis) { "RINEX GPS reference outside UTC resource" }
        return RinexGpsOrbitProvider(parsed, GnssOrbitProvenance("rinex-gps", sha256, sourceUrl, epoch,
            GnssOrbitSourceKind.BROADCAST_EPHEMERIS), utc)
    }

    /** Native clock-calendar high-water marks; never compare them as inter-system UTC instants. */
    internal fun epochLabels(): Map<RinexNavigationSystem, String> = parsed.records.groupBy { it.system }.mapValues { (_, records) ->
        records.maxOf { r -> "%04d-%02d-%02dT%02d:%02d:%02d".format(java.util.Locale.ROOT,
            r.epoch.date.year, r.epoch.date.monthValue, r.epoch.date.dayOfMonth,
            r.epoch.hour, r.epoch.minute, r.epoch.second) }
    }

    companion object {
        const val MAX_NAVIGATION_BYTES = 1_048_576
        const val MAX_JSON_BYTES = 1_200_000
        const val MAX_WIRE_LENGTH = 131_072
        private val json = Json { encodeDefaults = true }

        fun import(navigation: String, sourceUrl: String, downloadedAtMillis: Long): RinexNavigationResource =
            RinexNavigationResource(sourceUrl = sourceUrl, sha256 = digest(navigation),
                downloadedAtMillis = downloadedAtMillis, navigation = navigation).validate()

        fun encode(resource: RinexNavigationResource): String = json.encodeToString(serializer(), resource.validate()).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "RINEX JSON resource too large" }
        }

        fun decode(text: String): RinexNavigationResource {
            require(text.length <= MAX_JSON_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
            return json.decodeFromString(serializer(), text).validate()
        }

        fun encodeWire(resource: RinexNavigationResource): String {
            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { it.write(encode(resource).toByteArray(Charsets.UTF_8)) }
            return Base64.getEncoder().encodeToString(output.toByteArray()).also {
                require(it.length <= MAX_WIRE_LENGTH) { "RINEX resource exceeds IPC budget" }
            }
        }

        fun decodeWire(encoded: String): RinexNavigationResource {
            require(encoded.isNotEmpty() && encoded.length <= MAX_WIRE_LENGTH) { "Invalid RINEX IPC size" }
            val output = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val size = input.read(chunk)
                    if (size < 0) break
                    require(output.size() + size <= MAX_JSON_BYTES) { "RINEX decompression limit" }
                    output.write(chunk, 0, size)
                }
            }
            return decode(output.toString(Charsets.UTF_8.name()))
        }

        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
