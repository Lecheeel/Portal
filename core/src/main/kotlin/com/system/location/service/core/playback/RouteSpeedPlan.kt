package com.system.location.service.core.playback

import com.system.location.service.core.geo.Wgs84
import net.sf.geographiclib.Geodesic
import kotlin.math.*

/** Deterministic acceleration/curvature plan. Time integration is analytic, not tick-dependent. */
internal class RouteSpeedPlan(points: List<Wgs84>, lengths: DoubleArray, cruise: Double) {
    private val distances: DoubleArray
    private val speeds: DoubleArray
    private val times: DoubleArray
    val duration: Double get() = times.last()
    init {
        require(cruise > 0)
        val caps = DoubleArray(points.size) { cruise }
        caps[0] = 0.0
        caps[caps.lastIndex] = 0.0
        for (i in 1 until points.lastIndex) {
            val a = lengths[i - 1]; val b = lengths[i]
            if (a < 0.001 || b < 0.001) continue
            val incoming = Geodesic.WGS84.Inverse(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude)
            val outgoing = Geodesic.WGS84.Inverse(points[i].latitude, points[i].longitude, points[i + 1].latitude, points[i + 1].longitude)
            val turn = abs((outgoing.azi1 - incoming.azi2 + 540) % 360 - 180)
            if (turn < 1) continue
            val c = Geodesic.WGS84.Inverse(points[i - 1].latitude, points[i - 1].longitude, points[i + 1].latitude, points[i + 1].longitude).s12
            val s = (a + b + c) / 2
            val area = sqrt(max(0.0, s * (s - a) * (s - b) * (s - c)))
            val radius = if (turn > 175 || area < 1e-8) 0.5 else a * b * c / (4 * area)
            caps[i] = min(cruise, sqrt(2.2 * max(0.5, radius)))
        }
        val d = arrayListOf(0.0); val v = arrayListOf(0.0)
        var offset = 0.0
        fun add(distance: Double, speed: Double) {
            if (distance - d.last() > 1e-9) { d += distance; v += speed }
            else v[v.lastIndex] = min(v.last(), speed)
        }
        for (i in lengths.indices) {
            val length = lengths[i]
            // Enough knots to reach cruise on long straights and accelerate on two-point routes.
            val ramp = min(length / 2, cruise * cruise / (2 * ACCELERATION))
            add(offset + ramp, cruise)
            add(offset + length - ramp, cruise)
            offset += length
            add(offset, caps[i + 1])
        }
        distances = d.toDoubleArray(); speeds = v.toDoubleArray()
        for (i in 1..speeds.lastIndex) speeds[i] = min(speeds[i], sqrt(speeds[i - 1].pow(2) + 2 * ACCELERATION * (distances[i] - distances[i - 1])))
        for (i in speeds.lastIndex - 1 downTo 0) speeds[i] = min(speeds[i], sqrt(speeds[i + 1].pow(2) + 2 * ACCELERATION * (distances[i + 1] - distances[i])))
        times = DoubleArray(distances.size)
        for (i in 1..times.lastIndex) times[i] = times[i - 1] + 2 * (distances[i] - distances[i - 1]) / (speeds[i - 1] + speeds[i])
    }
    fun at(time: Double): Pair<Double, Double> {
        if (time >= duration) return distances.last() to 0.0
        val i = floorIndex(times, time.coerceAtLeast(0.0))
        val dt = (time - times[i]).coerceAtLeast(0.0)
        val a = (speeds[i + 1] - speeds[i]) / (times[i + 1] - times[i])
        return (distances[i] + speeds[i] * dt + a * dt * dt / 2).coerceIn(distances[i], distances[i + 1]) to max(0.0, speeds[i] + a * dt)
    }
    fun timeAt(distance: Double): Double {
        if (distance >= distances.last()) return duration
        val i = floorIndex(distances, distance.coerceAtLeast(0.0))
        val dx = (distance - distances[i]).coerceAtLeast(0.0)
        val a = (speeds[i + 1].pow(2) - speeds[i].pow(2)) / (2 * (distances[i + 1] - distances[i]))
        val endSpeed = sqrt(max(0.0, speeds[i].pow(2) + 2 * a * dx))
        return times[i] + if (dx == 0.0) 0.0 else 2 * dx / (speeds[i] + endSpeed)
    }
    private fun floorIndex(values: DoubleArray, value: Double): Int {
        var lo = 0; var hi = values.lastIndex
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (values[mid] <= value) lo = mid else hi = mid - 1 }
        return lo.coerceAtMost(values.lastIndex - 1)
    }
    companion object { private const val ACCELERATION = 1.5 }
}
