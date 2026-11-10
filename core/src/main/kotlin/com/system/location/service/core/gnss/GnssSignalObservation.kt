package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

/** One valid GnssStatus record, including a repeated SVID on another carrier. */
@Serializable
data class GnssSignalObservation(
    val satellite: GnssSatellite,
    val basebandCn0DbHz: Float? = null,
) {
    init {
        require(basebandCn0DbHz == null || basebandCn0DbHz in 0f..GnssFrame.MAX_CN0_DB_HZ) {
            "invalid baseband CN0"
        }
        require(satellite.carrierHz == null || satellite.carrierHz.isFinite() && satellite.carrierHz > 0f) {
            "invalid signal carrier"
        }
    }
}

/**
 * Spacecraft statistics and geometry count an identity once, independent of callback array order.
 * Prefer a usable signal, then a used signal and the lowest reported frequency. Keep all fields from that same record;
 * neither CN0 nor geometry is averaged across bands. Original records remain in the capture.
 */
object GnssSignalProjection {
    fun spacecraft(signals: List<GnssSignalObservation>): List<GnssSatellite> = signals
        .map { it.satellite }
        .groupBy { it.constellation to it.svid }
        .values.map { candidates -> candidates.minWith(compareBy<GnssSatellite>(
            { !GnssCaptureValidator.isUsableSatellite(it) },
            { !it.usedInFix }, { it.carrierHz ?: Float.POSITIVE_INFINITY },
            { -it.cn0DbHz }, { it.elevationDeg }, { it.azimuthDeg },
            { !it.hasEphemeris }, { !it.hasAlmanac },
        )) }
        .sortedWith(compareBy({ it.constellation.ordinal }, { it.svid }))
}
