package com.system.location.service.ui.home

import com.system.location.service.hook.utils.FakeLoc

/** Suppress stale fixes and stationary GPS noise, without filtering simulated movement. */
class PositionFilter {
    private var lastTime = 0L
    private var last: Pair<Double, Double>? = null

    fun accept(point: Pair<Double, Double>, time: Long, accuracy: Float, speed: Float): Pair<Double, Double>? {
        if (point.first !in -90.0..90.0 || point.second !in -180.0..180.0 || time <= lastTime) return null
        lastTime = time
        val previous = last
        if (previous != null && speed < 0.8f &&
            FakeLoc.haversine(previous.first, previous.second, point.first, point.second) < accuracy.coerceIn(2f, 5f)) {
            return previous
        }
        return point.also { last = it }
    }
}
