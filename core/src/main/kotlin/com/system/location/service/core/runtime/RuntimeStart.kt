package com.system.location.service.core.runtime

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSanitizer

/** A complete, validated start transition. Missing optional fields merge against the CAS input. */
data class RuntimeStart(
    val coordinate: Wgs84,
    val speedMps: Double? = null,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val reportIntervalMs: Long = 100L,
) {
    init {
        speedMps?.let { require(it.isFinite() && it in 0.0..LocationSanitizer.MAX_SPEED_MPS) }
        altitudeMeters?.let {
            require(it.isFinite() && it in LocationSanitizer.MIN_ALTITUDE_METERS..LocationSanitizer.MAX_ALTITUDE_METERS)
        }
        accuracyMeters?.let { require(it.isFinite() && it > 0f) }
        require(reportIntervalMs in 50L..1000L)
    }

    fun applyTo(current: RuntimeSnapshot): RuntimeSnapshot = current.copy(
        configRevision = Math.addExact(current.configRevision, 1L),
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        speedMps = speedMps ?: current.speedMps,
        altitudeMeters = altitudeMeters ?: current.altitudeMeters,
        accuracyMeters = accuracyMeters ?: current.accuracyMeters,
        sample = null,
        provider = null,
        configuration = current.configuration.copy(enabled = true, reportIntervalMs = reportIntervalMs),
    )
}
