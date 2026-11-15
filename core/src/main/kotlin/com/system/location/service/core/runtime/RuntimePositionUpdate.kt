package com.system.location.service.core.runtime

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSanitizer

/** An absolute target with optional heading; validation precedes all live position/motion changes. */
data class RuntimePositionUpdate(
    val coordinate: Wgs84,
    val movementSpeedMps: Float = 0f,
    val bearingDeg: Double? = null,
) {
    init {
        require(movementSpeedMps.isFinite() && movementSpeedMps in 0f..LocationSanitizer.MAX_SPEED_MPS.toFloat())
        require(bearingDeg == null || bearingDeg.isFinite())
    }

    fun applyTo(current: RuntimeSnapshot): RuntimeSnapshot = current.copy(
        latitude = coordinate.latitude, longitude = coordinate.longitude, sample = null, provider = null,
        bearingDeg = bearingDeg ?: current.bearingDeg,
        hasBearings = if (bearingDeg == null) current.hasBearings else true,
        configRevision = Math.addExact(current.configRevision, 1L),
    )
}
