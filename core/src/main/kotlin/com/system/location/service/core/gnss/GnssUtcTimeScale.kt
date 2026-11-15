package com.system.location.service.core.gnss

/** An effective offset at a POSIX UTC instant, from a versioned IERS leap-second resource. */
data class GnssUtcOffset(val effectiveAtMillis: Long, val taiMinusUtcSeconds: Int)

data class UtcGpsConversion(val time: GpsTime?, val reason: String? = null)
data class GpsUtcConversion(val utcMillis: Long?, val reason: String? = null)

/**
 * Converts representable POSIX UTC instants to nominal GPS time using TAI-GPS = 19 seconds.
 * POSIX cannot represent 23:59:60; this API does not invent a UTC leap-second timestamp.
 * Expired tables do not silently assume that no future leap second has occurred.
 * Sub-microsecond system-clock realizations require separate broadcast time corrections.
 * Resource format: https://hpiers.obspm.fr/iers/bul/bulc/ntp/leap-seconds.list
 */
class GnssUtcTimeScale(offsets: List<GnssUtcOffset>, val expiresAtMillis: Long) {
    private val offsets = offsets.toList()

    init {
        require(offsets.isNotEmpty() && offsets.first().effectiveAtMillis <= GPS_EPOCH_MILLIS)
        require(offsets.all { it.taiMinusUtcSeconds in 0..1000 })
        require(offsets.zipWithNext().all { (a, b) ->
            b.effectiveAtMillis > a.effectiveAtMillis &&
                kotlin.math.abs(b.taiMinusUtcSeconds - a.taiMinusUtcSeconds) == 1
        })
        require(expiresAtMillis > offsets.last().effectiveAtMillis && expiresAtMillis > GPS_EPOCH_MILLIS)
        require(offsets.last { it.effectiveAtMillis <= GPS_EPOCH_MILLIS }.taiMinusUtcSeconds == 19)
    }

    fun gpsAtUtcMillis(utcMillis: Long): UtcGpsConversion {
        if (utcMillis < GPS_EPOCH_MILLIS) return UtcGpsConversion(null, "UTC precedes GPS epoch")
        if (utcMillis >= expiresAtMillis) return UtcGpsConversion(null, "UTC leap-second resource expired")
        val offset = offsets.last { it.effectiveAtMillis <= utcMillis }.taiMinusUtcSeconds - 19
        return convert(utcMillis, offset)
    }

    /**
     * Last-known-offset conversion used only when the bundled IERS table has expired.
     *
     * A future leap second cannot be inferred; the caller gets a deterministic GPS time from the
     * last published TAI-UTC value instead of losing the whole synthetic receiver. This is a
     * degraded fallback, not a claim that the table is current.
     */
    fun gpsAtUtcMillisClamped(utcMillis: Long): UtcGpsConversion {
        if (utcMillis < GPS_EPOCH_MILLIS) return UtcGpsConversion(null, "UTC precedes GPS epoch")
        val offset = offsets.last().taiMinusUtcSeconds - 19
        return convert(utcMillis, offset).copy(
            reason = "UTC leap-second resource expired; using last known offset",
        )
    }

    private fun convert(utcMillis: Long, offsetSeconds: Int): UtcGpsConversion {
        // Work in milliseconds relative to the origin; preserve sub-second precision before
        // converting only the within-week remainder to Double.
        val gpsMillis = utcMillis - GPS_EPOCH_MILLIS + offsetSeconds * 1000L
        val week = gpsMillis / MILLIS_PER_WEEK
        if (week > Int.MAX_VALUE) return UtcGpsConversion(null, "GPS week outside supported range")
        return UtcGpsConversion(GpsTime(week.toInt(), gpsMillis % MILLIS_PER_WEEK / 1000.0))
    }

    /** Millisecond-resolution inverse; the inserted leap second has no POSIX representation. */
    fun utcAtGpsTime(time: GpsTime): GpsUtcConversion {
        val gpsMillis = time.week * MILLIS_PER_WEEK + kotlin.math.round(time.secondsOfWeek * 1000).toLong()
        val candidates = offsets.map { it.taiMinusUtcSeconds - 19 }.distinct().map { offset ->
            GPS_EPOCH_MILLIS + gpsMillis - offset * 1000L
        }.filter { candidate ->
            val decoded = gpsAtUtcMillis(candidate).time
            decoded != null && kotlin.math.abs(decoded.secondsSince(time)) <= 0.000501
        }
        return if (candidates.size == 1) GpsUtcConversion(candidates.single())
        else GpsUtcConversion(null, "GPS instant outside UTC resource or not representable in POSIX UTC")
    }

    companion object {
        const val GPS_EPOCH_MILLIS = 315_964_800_000L
        private const val MILLIS_PER_WEEK = 604_800_000L
        private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L

        fun parseIers(text: String): GnssUtcTimeScale {
            require(text.toByteArray(Charsets.UTF_8).size <= 65536) { "UTC resource too large" }
            var expiry: Long? = null
            val offsets = mutableListOf<GnssUtcOffset>()
            fun ntpMillis(token: String): Long = Math.multiplyExact(
                Math.subtractExact(token.toLong(), NTP_UNIX_OFFSET_SECONDS), 1000)
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("#@")) {
                    require(expiry == null) { "Duplicate UTC expiry" }
                    expiry = ntpMillis(line.substring(2).trim().substringBefore('#').trim())
                } else if (line.isNotEmpty() && !line.startsWith('#')) {
                    val parts = line.substringBefore('#').trim().split(Regex("\\s+"))
                    require(parts.size == 2 && offsets.size < 256) { "Invalid UTC offset record" }
                    offsets += GnssUtcOffset(ntpMillis(parts[0]), parts[1].toInt())
                }
            }
            return GnssUtcTimeScale(offsets, requireNotNull(expiry) { "Missing UTC resource expiry" })
        }
    }
}
