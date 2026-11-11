package com.system.location.service.gnss

import com.system.location.service.core.gnss.RinexNavigationReader
import com.system.location.service.core.gnss.RinexNavigationResource
import com.system.location.service.core.gnss.RinexNavigationSnapshot
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.GZIPInputStream

/** No receiver position is sent. The server receives only a public UTC day/archive request. */
internal object GnssNavigationUrls {
    fun candidates(nowMillis: Long): List<String> {
        require(nowMillis >= 0)
        val date = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return listOf(date, date.minusDays(1)).map { day ->
            val year = "%04d".format(Locale.ROOT, day.year)
            val doy = "%03d".format(Locale.ROOT, day.dayOfYear)
            "https://igs.bkg.bund.de/root_ftp/IGS/BRDC/$year/$doy/BRDC00WRD_R_${year}${doy}0000_01D_MN.rnx.gz"
        }
    }
}

internal fun interface GnssNavigationTransport {
    /** Null means HTTP 404 (day not published). Other failures must retain the old cache. */
    fun download(url: String): String?
}

internal class HttpsGnssNavigationTransport : GnssNavigationTransport {
    override fun download(url: String): String? {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "igs.bkg.bund.de" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path.matches(Regex("/root_ftp/IGS/BRDC/[0-9]{4}/[0-9]{3}/BRDC00WRD_R_[0-9]{11}_01D_MN\\.rnx\\.gz")))
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Portal-GNSS")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status == 404) return null
            check(status == 200) { "GNSS archive HTTP $status" }
            require(connection.contentLengthLong <= MAX_COMPRESSED_BYTES) { "GNSS archive too large" }
            val deadline = System.nanoTime() + 30_000_000_000L
            val compressed = connection.inputStream.use { readBounded(it, MAX_COMPRESSED_BYTES, deadline) }
            return GZIPInputStream(compressed.inputStream()).use {
                readBounded(it, RinexNavigationReader.MAX_BYTES, deadline).toString(Charsets.US_ASCII)
            }
        } finally { connection.disconnect() }
    }

    private fun readBounded(input: InputStream, limit: Int, deadline: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) { "GNSS archive read interrupted or timed out" }
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "GNSS archive expansion exceeds budget" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object { const val MAX_COMPRESSED_BYTES = 4 * 1024 * 1024 }
}

/** Complete and validate the candidate before the repository touches the last good cache. */
internal class GnssNavigationDownload(private val transport: GnssNavigationTransport) {
    fun fetch(nowMillis: Long): RinexNavigationResource {
        for (url in GnssNavigationUrls.candidates(nowMillis)) {
            val archive = transport.download(url) ?: continue
            return RinexNavigationSnapshot.select(archive, url, nowMillis)
        }
        error("GNSS navigation archives are not published for the current or previous UTC day")
    }
}
