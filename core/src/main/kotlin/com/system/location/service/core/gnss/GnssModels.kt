package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable

enum class Constellation { GPS, BEIDOU, GLONASS, GALILEO, QZSS, SBAS, IRNSS }

@Serializable
data class GnssSatellite(
    val constellation: Constellation,
    val svid: Int,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val carrierHz: Float? = null,
) {
    init {
        require(svid in 1..999) { "svid out of range: $svid" }
        require(elevationDeg in -90f..90f) { "elevation out of range: $elevationDeg" }
        require(azimuthDeg in 0f..360f) { "azimuth out of range: $azimuthDeg" }
        require(cn0DbHz in 0f..60f) { "CN0 out of range: $cn0DbHz" }
    }
}

@Serializable
data class GnssFrame(
    val generatedAtMillis: Long,
    val satellites: List<GnssSatellite>,
    val hdop: Float,
    val vdop: Float,
    val pdop: Float,
) {
    init {
        require(generatedAtMillis >= 0L) { "generatedAtMillis must be non-negative" }
        require(satellites.size <= MAX_SATELLITES) { "Too many satellites: ${satellites.size}" }
        require(satellites.map { it.constellation to it.svid }.distinct().size == satellites.size) {
            "Duplicate constellation/svid"
        }
        require(hdop > 0f && vdop > 0f && pdop > 0f) { "DOP values must be positive" }
    }

    val usedInFix: List<GnssSatellite> get() = satellites.filter { it.usedInFix }

    fun countFor(constellation: Constellation): Int = satellites.count { it.constellation == constellation }

    companion object {
        const val MAX_SATELLITES = 128
        const val MAX_CN0_DB_HZ = 60f
    }
}

/** Single source for GnssStatus, NMEA and (later) raw measurements (ADR-006). */
interface GnssSkyModel {
    fun frame(atMillis: Long, observer: Wgs84, altitudeMeters: Double): GnssFrame
}
