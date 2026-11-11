package com.system.location.service.core.gnss

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Conservative adapter for a SINEX producer whose epoch time scale is not explicitly stated.
 * Only accept an assignment invariant over nominal UTC through TAI (covering BDT/GPS/GST),
 * plus one second on either side for fractional realizations/rounding. This is not a conversion
 * claiming all these time scales coincide. A boundary, overlap or gap makes identity unknown.
 * Creation-year evidence comes from the source manifest. Open-ended assignments have a caller-
 * supplied maximum catalog age; publication age never changes actual assignment intervals.
 */
class BeiDouSkyCatalog(
    private val metadata: BeiDouSatelliteMetadata,
    fullCreationYear: Int,
    maximumOpenIntervalAgeSeconds: Long,
    private val utc: GnssUtcTimeScale,
) {
    private val lastAcceptedLabel: LocalDateTime
    init {
        require(maximumOpenIntervalAgeSeconds in 1..366 * 86400L)
        lastAcceptedLabel = metadata.creationAt(fullCreationYear).plusSeconds(maximumOpenIntervalAgeSeconds)
    }

    fun identityAt(svid: Int, utcMillis: Long): BeiDouSatelliteIdentity? {
        if (svid !in 1..63) return null
        val gps = utc.gpsAtUtcMillis(utcMillis).time ?: return null
        val utcLabel = java.time.Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC).toLocalDateTime()
        val gpsLabel = java.time.Instant.ofEpochMilli(GnssUtcTimeScale.GPS_EPOCH_MILLIS)
            .atOffset(ZoneOffset.UTC).toLocalDateTime().plusWeeks(gps.week.toLong())
            .plusNanos(kotlin.math.round(gps.secondsOfWeek * 1e9).toLong())
        val taiLabel = gpsLabel.plusSeconds(19)
        // Include every supported nominal scale explicitly, even if future leap history
        // changes their order; do not silently narrow the uncertainty interval.
        val bdtLabel = gpsLabel.minusSeconds(14)
        val earliest = minOf(utcLabel, taiLabel, gpsLabel, bdtLabel).minusSeconds(1)
        val latest = maxOf(utcLabel, taiLabel, gpsLabel, bdtLabel).plusSeconds(1)
        // Treat the creation label with the same uncertainty, conservatively at its earliest.
        val uncertainty = java.time.Duration.between(earliest, latest)
        if (latest.isAfter(lastAcceptedLabel.minus(uncertainty))) return null
        return metadata.identityAt(svid, earliest, latest)
    }
}
