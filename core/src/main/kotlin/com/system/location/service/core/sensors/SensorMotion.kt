package com.system.location.service.core.sensors

import com.system.location.service.core.location.LocationSample

/** Converts an actual route fix into the sensor model's motion state. */
object SensorMotion {
    fun mode(speedMps: Double): MotionMode = when {
        !speedMps.isFinite() || speedMps < 0.3 -> MotionMode.STILL
        speedMps < 2.5 -> MotionMode.WALK
        speedMps < 7.0 -> MotionMode.RUN
        else -> MotionMode.VEHICLE
    }

    fun from(fix: LocationSample): MotionState = MotionState(
        mode = mode(fix.speed.toDouble()),
        speedMps = fix.speed.toDouble().coerceAtLeast(0.0),
        bearingDeg = (fix.bearing.toDouble().let { it % 360.0 + 360.0 } % 360.0)
            .coerceAtMost(359.99997),
        altitudeMeters = fix.altitude.toDouble().coerceIn(-500.0, 20_000.0),
    )
}
