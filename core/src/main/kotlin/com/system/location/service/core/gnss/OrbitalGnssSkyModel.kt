package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class OrbitalSatellite(
    val constellation: Constellation,
    val svid: Int,
    val elements: KeplerElements,
) {
    init {
        require(svid in 1..999) { "svid out of range: $svid" }
    }
}

/**
 * P3-05 V2 sky-model spike: Kepler elements -> ECI -> ECEF -> ENU.
 *
 * This is an independent model used for the feasibility report; the production hook still uses the
 * deterministic analytic [CatalogGnssSkyModel]. No perturbations, clock correction or ephemeris
 * decoding are applied.
 */
class OrbitalGnssSkyModel(
    private val satellites: List<OrbitalSatellite> = OrbitalCatalog.satellites,
) : GnssSkyModel {
    init {
        require(satellites.isNotEmpty()) { "satellites must not be empty" }
        require(satellites.map { it.constellation to it.svid }.distinct().size == satellites.size) {
            "duplicate constellation/svid"
        }
    }

    override fun frame(atMillis: Long, observer: Wgs84, altitudeMeters: Double): GnssFrame {
        require(atMillis >= 0L) { "atMillis must be non-negative" }
        val observerEcef = ecef(observer, altitudeMeters)
        val theta = gmstRad(atMillis)
        val satellites = satellites.mapNotNull { satellite ->
            val eci = runCatching { KeplerOrbit(satellite.elements).positionAt(atMillis / 1_000.0) }.getOrNull()
                ?: return@mapNotNull null
            val ecef = EciPosition(
                x = eci.x * cos(theta) + eci.y * sin(theta),
                y = -eci.x * sin(theta) + eci.y * cos(theta),
                z = eci.z,
            )
            val dx = ecef.x - observerEcef.x
            val dy = ecef.y - observerEcef.y
            val dz = ecef.z - observerEcef.z
            val lat = Math.toRadians(observer.latitude)
            val lon = Math.toRadians(observer.longitude)
            val east = -sin(lon) * dx + cos(lon) * dy
            val north = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
            val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
            val range = sqrt(east * east + north * north + up * up)
            if (range <= 0.0) return@mapNotNull null
            val elevation = Math.toDegrees(asin((up / range).coerceIn(-1.0, 1.0))).toFloat()
            val azimuth = ((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0).toFloat()
            val cn0 = (30.0 + 15.0 * sin(Math.toRadians(elevation.toDouble()))).coerceIn(5.0, 55.0).toFloat()
            GnssSatellite(
                constellation = satellite.constellation,
                svid = satellite.svid,
                elevationDeg = elevation,
                azimuthDeg = azimuth,
                cn0DbHz = cn0,
                usedInFix = elevation >= 10f,
                hasEphemeris = true,
                hasAlmanac = true,
            )
        }
        if (satellites.isEmpty()) throw IllegalStateException("No satellites produced")
        val used = satellites.count { it.usedInFix }.coerceAtLeast(1)
        val dop = (1.2 + 8.0 / used).toFloat()
        return GnssFrame(atMillis, satellites, dop, dop * 1.2f, dop * 1.6f)
    }

    private fun ecef(point: Wgs84, altitudeMeters: Double): EciPosition {
        val lat = Math.toRadians(point.latitude)
        val lon = Math.toRadians(point.longitude)
        val sinLat = sin(lat)
        val n = EARTH_RADIUS / sqrt(1.0 - E2 * sinLat * sinLat)
        return EciPosition(
            x = (n + altitudeMeters) * cos(lat) * cos(lon),
            y = (n + altitudeMeters) * cos(lat) * sin(lon),
            z = (n * (1.0 - E2) + altitudeMeters) * sinLat,
        )
    }

    private fun gmstRad(atMillis: Long): Double {
        val daysSinceJ2000 = atMillis / 86_400_000.0 - 10_957.5
        val turns = 0.7790572732640 + 1.00273781191135448 * daysSinceJ2000
        return 2.0 * PI * ((turns % 1.0 + 1.0) % 1.0)
    }

    private companion object {
        const val EARTH_RADIUS = 6_378_137.0
        const val E2 = 6.69437999014e-3
    }
}

object OrbitalCatalog {
    val satellites: List<OrbitalSatellite> = listOf(
        OrbitalSatellite(Constellation.GPS, 1, KeplerElements(26_560_000.0, 0.01, 0.97, 0.30, 0.50, 0.0, 0.0)),
        OrbitalSatellite(Constellation.GPS, 2, KeplerElements(26_560_000.0, 0.01, 0.97, 1.70, 2.10, 1.2, 0.0)),
        OrbitalSatellite(Constellation.BEIDOU, 1, KeplerElements(27_906_000.0, 0.001, 0.95, 2.60, 0.90, 2.2, 0.0)),
        OrbitalSatellite(Constellation.GALILEO, 1, KeplerElements(29_600_000.0, 0.001, 0.97, 4.10, 1.60, 3.1, 0.0)),
        OrbitalSatellite(Constellation.GLONASS, 1, KeplerElements(25_510_000.0, 0.002, 1.13, 5.20, 2.40, 4.0, 0.0)),
    )
}
