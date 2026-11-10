package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84
import kotlin.math.abs

enum class ReceiverGnssSource { STATISTICAL_REPLAY, BROADCAST_EPHEMERIS, HYBRID_BROADCAST_REPLAY }

/** Candidate usage is retained until the final combined sky has been solved. */
internal data class ReceiverGnssSelection(
    val satellites: List<GnssSatellite>,
    val diagnostics: List<String>,
    val capacity: Int,
    /** Complete local empirical pool; requesting a lower bound must not truncate evidence. */
    val evidencePool: List<GnssSatellite>,
)

data class ReceiverGnssFrame(
    val generatedAtMillis: Long,
    val observer: Wgs84,
    val altitudeMeters: Double,
    val satellites: List<GnssSatellite>,
    val geometry: GnssGeometryResult,
    val source: ReceiverGnssSource,
    val diagnostics: List<String> = emptyList(),
    val availableSatelliteCapacity: Int = satellites.size,
    val requestedMinimumSatellites: Int = 0,
) {
    init {
        require(generatedAtMillis >= 0 && altitudeMeters.isFinite())
        // The legacy minimum-setting range is not a ceiling on a measured visible sky.
        require(availableSatelliteCapacity >= satellites.size)
        require(requestedMinimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        require(satellites.all { it.elevationDeg >= 0f && it.azimuthDeg < 360f })
        require(satellites.map { it.constellation to it.svid }.distinct().size == satellites.size)
    }
}

/**
 * Empirical statistical replay, explicitly not orbital propagation. A triangular replay clock
 * traverses the capture forward/backward, so a loop never jumps from the final sky to the first.
 * CN0 and its joint relationship with constellation/elevation come from actual paired samples;
 * interpolation retains that measured relationship instead of fitting an invented sine curve.
 */
class ReceiverGnssSkyModel(private val capture: GnssCapture, private val seed: Long = 0) {
    private val ratios = capture.profile().constellations.associate { it.constellation to it.ratio }
    private val frames = capture.frames.map { frame -> frame.copy(satellites = frame.satellites.filter {
        GnssCaptureValidator.isUsableSatellite(it) && GnssSatelliteIds.nmeaId(it.constellation, it.svid) != null
    }) }
    private val excluded = capture.frames.flatMap { it.satellites }.filter {
        GnssSatelliteIds.nmeaId(it.constellation, it.svid) == null
    }.map { "${it.constellation}:${it.svid}" }.distinct()
    fun frame(atMillis: Long, observer: Wgs84, altitudeMeters: Double, minimumSatellites: Int = 0): ReceiverGnssFrame? {
        require(atMillis >= 0 && altitudeMeters.isFinite())
        val selected = selection(atMillis, minimumSatellites) ?: return null
        val geometry = GnssGeometry.solve(selected.satellites)
        val diagnostics = selected.diagnostics.toMutableList()
        geometry.reason?.let { diagnostics += "无有效三维定位解：$it" }
        val output = if (geometry.dop == null) selected.satellites.map { it.copy(usedInFix = false) } else selected.satellites
        return ReceiverGnssFrame(atMillis, observer, altitudeMeters, output, geometry,
            ReceiverGnssSource.STATISTICAL_REPLAY, diagnostics, selected.capacity, minimumSatellites)
    }

    internal fun selection(atMillis: Long, minimumSatellites: Int): ReceiverGnssSelection? {
        require(atMillis >= 0)
        require(minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        if (!GnssCaptureValidator.validate(capture, atMillis).eligible) return null
        val first = frames.first().capturedAtMillis
        val duration = frames.last().capturedAtMillis - first
        val period = duration * 2
        val phase = (Math.floorMod(atMillis - first, period) + Math.floorMod(seed, period)) % period
        val replayAt = first + if (phase <= duration) phase else period - phase
        val rightIndex = frames.indexOfFirst { it.capturedAtMillis >= replayAt }.coerceAtLeast(0)
        val left = frames[maxOf(0, rightIndex - 1)]
        val right = frames[rightIndex]
        val span = right.capturedAtMillis - left.capturedAtMillis
        val fraction = if (span == 0L) 0.0 else (replayAt - left.capturedAtMillis).toDouble() / span
        val base = if (fraction < 0.5) left else right
        val leftById = left.satellites.associateBy { it.constellation to it.svid }
        val rightById = right.satellites.associateBy { it.constellation to it.svid }
        val selected = base.satellites.filter { it.elevationDeg >= 0f }.map { satellite ->
            val identity = satellite.constellation to satellite.svid
            val a = leftById[identity]
            val b = rightById[identity]
            if (a == null || b == null) satellite.copy(azimuthDeg = satellite.azimuthDeg % 360f)
            else interpolate(a, b, fraction)
        }.toMutableList()
        val diagnostics = mutableListOf("统计回放：局部天空形态来自实测画像，不代表目标地点的精确星历")
        if (excluded.isNotEmpty()) diagnostics += "统一排除无明确 NMEA 编号映射的卫星：${excluded.joinToString()}"
        // Capacity uses the same local evidence pool as allocation, including the current frame.
        val pool = (selected + frames.filter { abs(it.capturedAtMillis - replayAt) <= POOL_WINDOW_MILLIS }
            .sortedBy { abs(it.capturedAtMillis - replayAt) }
            .flatMap { it.satellites }).filter { it.elevationDeg >= 0f }
            .distinctBy { it.constellation to it.svid }
        val capacity = pool.size
        if (selected.size < minimumSatellites) {
            // Only reuse genuinely observed identities. Never lift a below-horizon observation.
            while (selected.size < minimumSatellites) {
                val identities = selected.map { it.constellation to it.svid }.toSet()
                val candidate = pool.filter { (it.constellation to it.svid) !in identities }.maxByOrNull { satellite ->
                    (ratios[satellite.constellation] ?: 0.0) * (selected.size + 1) -
                        selected.count { it.constellation == satellite.constellation }
                } ?: break
                selected += candidate.copy(azimuthDeg = candidate.azimuthDeg % 360f)
            }
            if (selected.size < minimumSatellites) diagnostics += "卫星下限 $minimumSatellites 无法满足，当前实测池仅 ${selected.size} 颗"
        }
        val ordered = selected.sortedWith(compareBy({ it.constellation.ordinal }, { it.svid }))
        return ReceiverGnssSelection(ordered, diagnostics, capacity, pool)
    }

    private companion object { const val POOL_WINDOW_MILLIS = 10_000L }

    private fun interpolate(a: GnssSatellite, b: GnssSatellite, fraction: Double): GnssSatellite {
        fun mix(x: Float, y: Float) = (x + (y - x) * fraction).toFloat()
        val arc = ((b.azimuthDeg - a.azimuthDeg + 540f) % 360f) - 180f
        val nearest = if (fraction < 0.5) a else b
        return nearest.copy(elevationDeg = mix(a.elevationDeg, b.elevationDeg),
            azimuthDeg = ((a.azimuthDeg + arc * fraction + 360.0) % 360.0).toGnssAzimuthFloat(),
            cn0DbHz = mix(a.cn0DbHz, b.cn0DbHz))
    }
}
