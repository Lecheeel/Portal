package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlin.math.*

data class EcefPosition(val xMeters: Double, val yMeters: Double, val zMeters: Double) {
    init { require(listOf(xMeters, yMeters, zMeters).all(Double::isFinite)) }
}

data class GnssLineOfSight(
    val eastMeters: Double,
    val northMeters: Double,
    val upMeters: Double,
    val rangeMeters: Double,
    val elevationDegrees: Double,
    /** Undefined at exact zenith/nadir; callers must make any presentation convention explicit. */
    val azimuthDegrees: Double?,
) {
    val aboveHorizon: Boolean get() = elevationDegrees >= 0
}

/** WGS 84 geodetic latitude and ellipsoid height, not geocentric latitude or MSL height. */
object GnssObserverGeometry {
    const val SEMI_MAJOR_AXIS_METERS = 6378137.0
    const val INVERSE_FLATTENING = 298.257223563
    private val flattening = 1 / INVERSE_FLATTENING
    private val eccentricitySquared = flattening * (2 - flattening)

    fun ecef(observer: Wgs84, ellipsoidHeightMeters: Double): EcefPosition {
        require(ellipsoidHeightMeters.isFinite())
        val latitude = Math.toRadians(observer.latitude)
        val longitude = Math.toRadians(observer.longitude)
        val sinLatitude = sin(latitude)
        val normal = SEMI_MAJOR_AXIS_METERS / sqrt(1 - eccentricitySquared * sinLatitude * sinLatitude)
        return EcefPosition((normal + ellipsoidHeightMeters) * cos(latitude) * cos(longitude),
            (normal + ellipsoidHeightMeters) * cos(latitude) * sin(longitude),
            (normal * (1 - eccentricitySquared) + ellipsoidHeightMeters) * sinLatitude)
    }

    /** Both positions must use the same ECEF axes and epoch (apply transit rotation first). */
    fun lineOfSight(observer: Wgs84, ellipsoidHeightMeters: Double, satellite: EcefPosition): GnssLineOfSight? {
        val receiver = ecef(observer, ellipsoidHeightMeters)
        val latitude = Math.toRadians(observer.latitude)
        val longitude = Math.toRadians(observer.longitude)
        val x = satellite.xMeters - receiver.xMeters
        val y = satellite.yMeters - receiver.yMeters
        val z = satellite.zMeters - receiver.zMeters
        val east = -sin(longitude) * x + cos(longitude) * y
        val north = -sin(latitude) * cos(longitude) * x - sin(latitude) * sin(longitude) * y + cos(latitude) * z
        val up = cos(latitude) * cos(longitude) * x + cos(latitude) * sin(longitude) * y + sin(latitude) * z
        val horizontal = hypot(east, north)
        val range = hypot(horizontal, up)
        if (!range.isFinite() || range <= 0) return null
        // atan2 is equivalent to asin(up/range), without roundoff outside [-1,1].
        val elevation = Math.toDegrees(atan2(up, horizontal))
        // A relative tolerance handles floating-point residue at poles/zenith; no orbit is moved.
        val azimuth = if (horizontal <= range * 1e-14) null
            else (Math.toDegrees(atan2(east, north)) + 360) % 360
        return GnssLineOfSight(east, north, up, range, elevation, azimuth)
    }
}
