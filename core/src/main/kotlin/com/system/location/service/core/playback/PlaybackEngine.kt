package com.system.location.service.core.playback

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.scenario.*
import net.sf.geographiclib.Geodesic

data class PlaybackFrame(val sample: LocationSample, val segment: Int, val progress: Double, val completed: Boolean)

/** All distances use WGS84 geodesics; lookup is O(log N), regardless of time skipped. */
class PlaybackEngine(input: Scenario) {
    val scenario = input.frozen()
    private val points = scenario.route?.points?.let {
        if (scenario.mode == RouteMode.LOOP && it.first() != it.last()) it + it.first() else it
    } ?: listOf(scenario.point!!)
    private val lengths = DoubleArray((points.size - 1).coerceAtLeast(0)) { i ->
        Geodesic.WGS84.Inverse(points[i].latitude, points[i].longitude, points[i + 1].latitude, points[i + 1].longitude).s12
    }
    private val cumulative = DoubleArray(points.size).also { result ->
        lengths.forEachIndexed { i, length -> result[i + 1] = result[i] + length }
    }
    val totalDistance = cumulative.last()
    private var previousNanos: Long? = null
    private var travelled = 0.0
    private var paused = false
    private var point = points.first()
    private var manualBearing = 0.0
    private var manualMoving = false
    private var orbitEnabled = false
    private var orbitRadiusMeters = 0.2
    private var orbitPhase = 0.0
    private val orbitCenter = points.first()

    init { require(scenario.route == null || totalDistance > 0.001) { "Route has no movement" } }

    fun pause() { paused = true }
    fun resume(nowNanos: Long) { paused = false; previousNanos = nowNanos }
    fun setMotion(bearing: Double, moving: Boolean) {
        require(bearing.isFinite())
        manualBearing = (bearing % 360 + 360) % 360
        manualMoving = moving
    }

    fun setOrbit(enabled: Boolean, radiusMeters: Double) {
        require(radiusMeters.isFinite() && radiusMeters in 0.05..5.0)
        orbitEnabled = enabled
        orbitRadiusMeters = radiusMeters
    }

    fun tick(nowNanos: Long, wallTimeMillis: Long): PlaybackFrame {
        require(nowNanos >= 0 && wallTimeMillis >= 0)
        val now = maxOf(nowNanos, previousNanos ?: nowNanos)
        val dt = previousNanos?.let { (now - it) / 1_000_000_000.0 } ?: 0.0
        previousNanos = now
        val profile = scenario.profile
        if (scenario.route == null) {
            val moving = manualMoving && !paused
            if (moving && dt > 0) {
                val fix = Geodesic.WGS84.Direct(point.latitude, point.longitude, manualBearing, profile.speedMps * dt)
                point = Wgs84(fix.lat2, fix.lon2)
            } else if (orbitEnabled && !paused && !manualMoving && dt > 0) {
                val angularSpeed = (profile.speedMps.coerceAtLeast(0.05) / orbitRadiusMeters)
                orbitPhase = (orbitPhase + angularSpeed * dt) % (2.0 * Math.PI)
                val bearing = Math.toDegrees(orbitPhase)
                val fix = Geodesic.WGS84.Direct(orbitCenter.latitude, orbitCenter.longitude,
                    bearing, orbitRadiusMeters)
                point = Wgs84(fix.lat2, fix.lon2)
            }
            val orbiting = orbitEnabled && !paused && !manualMoving
            val outputBearing = if (orbiting) (Math.toDegrees(orbitPhase) + 90.0) else manualBearing
            return frame(point, outputBearing, if (moving) profile.speedMps else if (orbiting) profile.speedMps else 0.0,
                now, wallTimeMillis, 0, 0.0, false)
        }
        if (!paused) travelled += profile.speedMps * dt
        val finished = scenario.mode == RouteMode.ONCE && travelled >= totalDistance
        val cycle = when (scenario.mode) {
            RouteMode.ONCE -> travelled.coerceAtMost(totalDistance)
            RouteMode.LOOP -> travelled % totalDistance
            RouteMode.PING_PONG -> travelled % (2 * totalDistance)
        }
        val backwards = scenario.mode == RouteMode.PING_PONG && cycle > totalDistance
        val distance = if (backwards) 2 * totalDistance - cycle else cycle
        var lo = 0
        var hi = cumulative.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulative[mid] <= distance) lo = mid else hi = mid - 1
        }
        val segment = lo.coerceAtMost(lengths.lastIndex)
        val from = points[segment]
        val to = points[segment + 1]
        val inverse = Geodesic.WGS84.Inverse(from.latitude, from.longitude, to.latitude, to.longitude)
        val result = Geodesic.WGS84.Direct(from.latitude, from.longitude, inverse.azi1, distance - cumulative[segment])
        val coordinate = if (distance >= totalDistance) points.last() else Wgs84(result.lat2, result.lon2)
        val bearing = result.azi2 + if (backwards) 180 else 0
        return frame(coordinate, bearing, if (paused || finished) 0.0 else profile.speedMps,
            now, wallTimeMillis, segment, distance / totalDistance, finished)
    }

    private fun frame(point: Wgs84, bearing: Double, speed: Double, now: Long, wall: Long,
        segment: Int, progress: Double, complete: Boolean) = PlaybackFrame(
        LocationSample(point, scenario.profile.altitudeMeters, scenario.profile.accuracyMeters,
            speed.toFloat(), ((bearing % 360 + 360) % 360).toFloat().coerceAtMost(359.99997f), wall, now),
        segment, progress, complete)
}
