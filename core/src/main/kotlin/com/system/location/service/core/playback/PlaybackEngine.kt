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
    private var manualStrength = 1.0
    private var speedMps = scenario.profile.speedMps
    private var plan: RouteSpeedPlan? = makePlan(speedMps)
    private var routeSeconds = 0.0
    private var orbitEnabled = false
    private var orbitRadiusMeters = 0.2
    private var orbitPhase = 0.0
    private var orbitCenter = points.first()
    private var orbitPeriodSeconds = 20.0

    init { require(scenario.route == null || totalDistance > 0.001) { "Route has no movement" } }

    fun pause() { paused = true }
    fun resume(nowNanos: Long) { paused = false; previousNanos = nowNanos }
    fun setMotion(bearing: Double, moving: Boolean, strength: Double = 1.0) {
        require(bearing.isFinite() && strength.isFinite() && strength in 0.0..1.0)
        if (manualMoving && !moving) { orbitCenter = point; orbitPhase = 0.0 }
        manualBearing = (bearing % 360 + 360) % 360
        manualMoving = moving
        manualStrength = strength
    }

    fun setOrbit(enabled: Boolean, radiusMeters: Double, periodSeconds: Double = 20.0) {
        require(radiusMeters.isFinite() && radiusMeters in 0.05..5.0)
        require(periodSeconds.isFinite() && periodSeconds in 5.0..120.0)
        orbitEnabled = enabled
        orbitRadiusMeters = radiusMeters
        orbitPeriodSeconds = periodSeconds
    }
    fun setSpeed(speed: Double) {
        require(speed.isFinite() && speed in 0.0..1000.0)
        if (scenario.route != null && scenario.profile.smoothMotion && speed > 0) {
            val old = plan
            val time = old?.let {
                if (scenario.mode == RouteMode.ONCE) routeSeconds.coerceAtMost(it.duration)
                else routeSeconds % (it.duration * if (scenario.mode == RouteMode.PING_PONG) 2 else 1)
            } ?: 0.0
            val reverse = old != null && time > old.duration
            val distance = old?.at(if (reverse) 2 * old.duration - time else time)?.first ?: travelled
            plan = makePlan(speed)
            plan?.let { routeSeconds = if (reverse) 2 * it.duration - it.timeAt(distance) else it.timeAt(distance) }
        }
        speedMps = speed
    }
    private fun makePlan(speed: Double) = if (scenario.route != null && scenario.profile.smoothMotion && speed > 0) RouteSpeedPlan(points, lengths, speed) else null

    fun tick(nowNanos: Long, wallTimeMillis: Long): PlaybackFrame {
        require(nowNanos >= 0 && wallTimeMillis >= 0)
        val now = maxOf(nowNanos, previousNanos ?: nowNanos)
        val dt = previousNanos?.let { (now - it) / 1_000_000_000.0 } ?: 0.0
        previousNanos = now
        if (scenario.route == null) {
            val moving = manualMoving && !paused
            if (moving && dt > 0) {
                val fix = Geodesic.WGS84.Direct(point.latitude, point.longitude, manualBearing, speedMps * manualStrength * dt)
                point = Wgs84(fix.lat2, fix.lon2)
            } else if (orbitEnabled && !paused && !manualMoving && speedMps > 0 && dt > 0) {
                val angularSpeed = 2.0 * Math.PI / orbitPeriodSeconds
                orbitPhase = (orbitPhase + angularSpeed * dt) % (2.0 * Math.PI)
                val bearing = Math.toDegrees(orbitPhase)
                val fix = Geodesic.WGS84.Direct(orbitCenter.latitude, orbitCenter.longitude,
                    bearing, orbitRadiusMeters)
                point = Wgs84(fix.lat2, fix.lon2)
            }
            val orbiting = orbitEnabled && !paused && !manualMoving && speedMps > 0
            val outputBearing = if (orbiting) (Math.toDegrees(orbitPhase) + 90.0) else manualBearing
            return frame(point, outputBearing, if (moving) speedMps * manualStrength else if (orbiting) 2 * Math.PI * orbitRadiusMeters / orbitPeriodSeconds else 0.0,
                now, wallTimeMillis, 0, 0.0, false)
        }
        var outputSpeed = speedMps
        if (scenario.profile.smoothMotion) {
            plan?.let {
                if (!paused && speedMps > 0) routeSeconds += dt
                val time = when (scenario.mode) {
                    RouteMode.ONCE -> routeSeconds.coerceAtMost(it.duration)
                    RouteMode.LOOP -> routeSeconds % it.duration
                    RouteMode.PING_PONG -> routeSeconds % (2 * it.duration)
                }
                val backwards = scenario.mode == RouteMode.PING_PONG && time > it.duration
                val (distance, speed) = it.at(if (backwards) 2 * it.duration - time else time)
                travelled = if (backwards) 2 * totalDistance - distance else distance
                outputSpeed = if (speedMps > 0) speed else 0.0
            }
        } else if (!paused) travelled += speedMps * dt
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
        return frame(coordinate, bearing, if (paused || finished) 0.0 else outputSpeed,
            now, wallTimeMillis, segment, distance / totalDistance, finished)
    }

    private fun frame(point: Wgs84, bearing: Double, speed: Double, now: Long, wall: Long,
        segment: Int, progress: Double, complete: Boolean) = PlaybackFrame(
        LocationSample(point, scenario.profile.altitudeMeters, scenario.profile.accuracyMeters,
            speed.toFloat(), ((bearing % 360 + 360) % 360).toFloat().coerceAtMost(359.99997f), wall, now),
        segment, progress, complete)
}
