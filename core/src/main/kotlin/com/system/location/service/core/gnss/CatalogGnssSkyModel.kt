package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic V1 sky model (P3-01/P3-02).
 *
 * No `shuffled()` and no unseeded random: the catalog is fixed, azimuth rotates with Earth,
 * elevation/CN0 evolve smoothly with time, and the same `(time, observer)` always produces the same
 * frame. V2 (broadcast ephemeris/Kepler) is a separate task (P3-05).
 */
class CatalogGnssSkyModel(
    private val catalog: List<CatalogEntry> = DEFAULT_CATALOG,
) : GnssSkyModel {

    init {
        require(catalog.isNotEmpty()) { "catalog must not be empty" }
        require(catalog.map { it.constellation to it.svid }.distinct().size == catalog.size) {
            "catalog contains duplicate satellites"
        }
    }

    override fun frame(atMillis: Long, observer: Wgs84, altitudeMeters: Double): GnssFrame {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        val seconds = atMillis / 1_000.0
        val earthRotation = (seconds % SIDEREAL_DAY_SECONDS) * 360.0 / SIDEREAL_DAY_SECONDS
        val latitudeBias = observer.latitude / 4.0
        val altitudeBias = (altitudeMeters.coerceIn(-50.0, 4_000.0) / 4_000.0) * 0.8
        val satellites = catalog.map { entry ->
            val phase = entry.phaseRadians
            val elevation = (entry.baseElevation + 9.0 * sin(seconds / 900.0 + phase) + latitudeBias)
                .coerceIn(MIN_ELEVATION, MAX_ELEVATION)
            val rotation = earthRotation + observer.longitude / 3.0
            val azimuth = ((entry.baseAzimuth + rotation + 4.0 * cos(seconds / 1_200.0 + phase)) % 360.0 + 360.0) % 360.0
            val cn0 = (18.0 + elevation * 0.42 + entry.constellationCn0Offset + altitudeBias +
                1.4 * sin(seconds / 17.0 + phase)).coerceIn(MIN_CN0, GnssFrame.MAX_CN0_DB_HZ.toDouble())
            val used = elevation >= USED_IN_FIX_ELEVATION && cn0 >= MIN_USED_CN0
            GnssSatellite(
                constellation = entry.constellation,
                svid = entry.svid,
                elevationDeg = elevation.toFloat(),
                azimuthDeg = azimuth.toFloat(),
                cn0DbHz = cn0.toFloat(),
                usedInFix = used,
                hasEphemeris = elevation >= EPHEMERIS_ELEVATION,
                hasAlmanac = elevation >= ALMANAC_ELEVATION,
                carrierHz = entry.constellation.carrierHz(),
            )
        }.sortedWith(compareBy({ it.constellation.ordinal }, { it.svid }))

        val usedCount = satellites.count { it.usedInFix }.coerceAtLeast(1)
        val hdop = max(MIN_DOP, DOP_SCALE / sqrt(usedCount.toDouble()))
        val vdop = hdop * 1.35
        val pdop = hdop * 1.7
        return GnssFrame(
            generatedAtMillis = atMillis,
            satellites = satellites,
            hdop = hdop.toFloat(),
            vdop = vdop.toFloat(),
            pdop = pdop.toFloat(),
        )
    }

    /** Fixed catalog entry; `svid` follows each constellation's Android numbering. */
    data class CatalogEntry(
        val constellation: Constellation,
        val svid: Int,
        val baseElevation: Double,
        val baseAzimuth: Double,
        val phaseRadians: Double = 0.0,
        val constellationCn0Offset: Double = 0.0,
    )

    private fun Constellation.carrierHz(): Float? = when (this) {
        Constellation.GPS -> 1575.42e6f
        Constellation.BEIDOU -> 1561.098e6f
        Constellation.GLONASS -> 1602.0e6f
        Constellation.GALILEO -> 1575.42e6f
        Constellation.QZSS -> 1575.42e6f
        Constellation.SBAS -> 1575.42e6f
        Constellation.IRNSS -> 1176.45e6f
    }

    private companion object {
        const val SIDEREAL_DAY_SECONDS = 86_164.0
        const val MIN_ELEVATION = 5.0
        const val MAX_ELEVATION = 85.0
        const val USED_IN_FIX_ELEVATION = 15.0
        const val EPHEMERIS_ELEVATION = 12.0
        const val ALMANAC_ELEVATION = 8.0
        const val MIN_CN0 = 12.0
        const val MIN_USED_CN0 = 22.0
        const val MIN_DOP = 0.7
        const val DOP_SCALE = 5.0

        val DEFAULT_CATALOG: List<CatalogEntry> = buildList {
            // GPS: PRN 1–8.
            addAll(gps(1..8, 0.0))
            // BeiDou: PRN 1–8 (BDS PRNs overlap by design; constellation disambiguates).
            addAll(beidou(1..8, -1.2))
            // GLONASS: slot 1–7.
            addAll(glonass(1..7, -2.0))
            // Galileo: SVID 1–6.
            addAll(galileo(1..6, 0.8))
            // QZSS: SVID 1–2.
            addAll(qzss(1..2, 0.5))
        }

        fun gps(range: IntRange, cn0Offset: Double) = range.mapIndexed { index, svid ->
            CatalogEntry(Constellation.GPS, svid, 22.0 + (index % 6) * 7, index * 43.0, index * 0.7, cn0Offset)
        }

        fun beidou(range: IntRange, cn0Offset: Double) = range.mapIndexed { index, svid ->
            CatalogEntry(Constellation.BEIDOU, svid, 18.0 + (index % 7) * 6, index * 41.0 + 13, index * 0.9 + 0.4, cn0Offset)
        }

        fun glonass(range: IntRange, cn0Offset: Double) = range.mapIndexed { index, svid ->
            CatalogEntry(Constellation.GLONASS, svid, 15.0 + (index % 6) * 8, index * 48.0 + 27, index * 0.8 + 0.2, cn0Offset)
        }

        fun galileo(range: IntRange, cn0Offset: Double) = range.mapIndexed { index, svid ->
            CatalogEntry(Constellation.GALILEO, svid, 20.0 + (index % 5) * 7, index * 57.0 + 9, index * 0.75 + 0.6, cn0Offset)
        }

        fun qzss(range: IntRange, cn0Offset: Double) = range.mapIndexed { index, svid ->
            CatalogEntry(Constellation.QZSS, svid, 35.0 + index * 8, 140.0 + index * 55, index * 0.5, cn0Offset)
        }
    }
}
