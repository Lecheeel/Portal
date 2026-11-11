package com.system.location.service.core.sensors

import com.system.location.service.core.location.LocationSample

/** Route fixes update motion; sensor sampling runs independently on the monotonic event clock. */
class SensorMotionTracker {
    private var previous: LocationSample? = null
    private var motion: MotionState? = null

    fun reset() { previous = null; motion = null }

    fun accept(fix: LocationSample): MotionState {
        val old = previous
        if (old != null && fix.elapsedNanos <= old.elapsedNanos) return motion!!
        val dt = old?.let { (fix.elapsedNanos - it.elapsedNanos) / 1e9 }
        val derivative = dt != null && dt in 0.02..5.0
        val turn = if (derivative && old!!.speed >= 0.3f && fix.speed >= 0.3f) {
            Math.toRadians(((fix.bearing - old.bearing + 540.0) % 360.0) - 180.0) / dt!!
        } else 0.0
        return SensorMotion.from(fix).copy(
            forwardAccelerationMps2 = if (derivative) ((fix.speed - old!!.speed) / dt!!).coerceIn(-15.0, 15.0) else 0.0,
            turnRateRadPerSec = turn.coerceIn(-3.0, 3.0),
        ).also { previous = fix; motion = it }
    }
}
