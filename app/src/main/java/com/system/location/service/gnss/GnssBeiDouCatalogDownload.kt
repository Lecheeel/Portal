package com.system.location.service.gnss

import com.system.location.service.core.gnss.BeiDouCatalogResource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

internal fun interface GnssBeiDouCatalogDownload {
    fun fetch(nowMillis: Long): BeiDouCatalogResource
}

/** Fixed HTTPS endpoint, no redirects, bounded body and total read deadline. No location sent. */
internal class HttpsGnssBeiDouCatalogDownload : GnssBeiDouCatalogDownload {
    override fun fetch(nowMillis: Long): BeiDouCatalogResource {
        val connection = URI(BeiDouCatalogResource.SOURCE_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Portal-GNSS")
            connection.setRequestProperty("Accept-Encoding", "identity")
            check(connection.responseCode == 200) { "BeiDou metadata HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= MAX_BYTES) { "BeiDou metadata too large" }
            val modified = connection.lastModified
            require(modified > 0 && modified <= nowMillis && nowMillis >= 0) { "Invalid metadata Last-Modified evidence" }
            val deadline = System.nanoTime() + 30_000_000_000L
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) { "BeiDou metadata read interrupted or timed out" }
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(output.size() + size <= MAX_BYTES) { "BeiDou metadata exceeds budget" }
                    output.write(buffer, 0, size)
                }
            }
            return BeiDouCatalogResource.import(output.toString(Charsets.US_ASCII.name()), modified)
        } finally { connection.disconnect() }
    }

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
