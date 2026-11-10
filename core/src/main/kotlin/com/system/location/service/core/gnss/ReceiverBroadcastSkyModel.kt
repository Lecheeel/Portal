package com.system.location.service.core.gnss

import com.system.location.service.core.geo.Wgs84

/**
 * GPS broadcast geometry with capture-calibrated signal statistics. Other identities retain
 * explicitly labelled statistical geometry. A known broadcast identity is never resurrected
 * from the statistical pool after horizon, fit, freshness or calibration rejection.
 * This generates simulated status, not raw observables or proof of live signal availability.
 */
class ReceiverBroadcastSkyModel(private val capture: GnssCapture, seed: Long = 0) {
    private val replay = ReceiverGnssSkyModel(capture, seed)
    private val signal = GpsEmpiricalSignalModel(capture)
    private val evidence = if (capture.measurements.isNotEmpty() || capture.navigation.isNotEmpty())
        GpsBroadcastEvidence.fromCapture(capture) else capture.gpsBroadcastEvidence
    internal val broadcastBootCount: Int? get() = evidence?.clock?.bootCount
    private val ratios = capture.profile().constellations.associate { it.constellation to it.ratio }

    fun frame(atMillis: Long, observer: Wgs84, altitudeMeters: Double, minimumSatellites: Int = 0,
        elapsedRealtimeNanos: Long? = null, currentBootCount: Int? = null): ReceiverGnssFrame? {
        require(atMillis >= 0 && altitudeMeters.isFinite())
        require(minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        val e = evidence ?: return replay.frame(atMillis, observer, altitudeMeters, minimumSatellites)
        val time = elapsedRealtimeNanos?.let { e.clock.at(it, currentBootCount) }
        if (time == null) {
            val fallback = replay.frame(atMillis, observer, altitudeMeters, minimumSatellites) ?: return null
            return fallback.copy(diagnostics = fallback.diagnostics +
                "GPS 广播几何未启用：缺少同次开机的有效单调时间，当前明确使用统计回放")
        }
        val base = replay.selection(atMillis, 0) ?: return null
        val diagnostics = mutableListOf("GPS 方位/仰角来自广播轨道和真空光程；CN0、在用候选来自本次采集的经验校准，非实时接收状态")
        val broadcastIds = e.ephemerides.map { it.ephemeris.svid }.toSet()
        fun statistical(s: GnssSatellite) = s.constellation != Constellation.GPS || s.svid !in broadcastIds
        val physical = e.ephemerides.mapNotNull { decoded ->
            val id = decoded.ephemeris.svid
            val age = time.secondsSince(decoded.frameTransmissionTime.plusSeconds(18.0))
            if (age !in 0.0..GpsBroadcastEvidence.MAX_BROADCAST_AGE_SECONDS) {
                diagnostics += "GPS $id：广播状态证据超出模拟新鲜度窗口，未回退同身份统计几何"
                return@mapNotNull null
            }
            val result = GpsReceptionGeometry.observe(decoded.ephemeris, time, observer, altitudeMeters)
            val los = result.observation?.lineOfSight
            if (los == null) {
                diagnostics += "GPS $id：${result.reason}"
                return@mapNotNull null
            }
            if (!los.aboveHorizon) return@mapNotNull null
            val azimuth = los.azimuthDegrees
            if (azimuth == null) {
                diagnostics += "GPS $id：天顶方位未定义，当前状态适配器排除此观测"
                return@mapNotNull null
            }
            val calibration = signal.at(los.elevationDegrees)
            if (calibration == null) {
                diagnostics += "GPS $id：仰角超出本次 GPS L1/未知载波实测校准范围，未外推 CN0"
                return@mapNotNull null
            }
            GnssSatellite(Constellation.GPS, id, los.elevationDegrees.toFloat(),
                azimuth.toFloat().let { if (it >= 360f) 0f else it }, calibration.cn0DbHz.toFloat(),
                calibration.usedFraction >= 0.5 && calibration.cn0DbHz > 0.0,
                hasEphemeris = true, hasAlmanac = calibration.hasAlmanac, carrierHz = calibration.carrierHz)
        }.sortedBy { it.svid }
        // All genuinely observed, valid broadcast identities may contribute, including those
        // absent from the current statistical window. The remaining pool stays local empirical.
        val pool = physical + base.evidencePool.filter(::statistical)
        val selected = (physical + base.satellites.filter(::statistical)).toMutableList()
        while (selected.size < minimumSatellites) {
            val ids = selected.map { it.constellation to it.svid }.toSet()
            val next = pool.filter { (it.constellation to it.svid) !in ids }.maxByOrNull {
                (ratios[it.constellation] ?: 0.0) * (selected.size + 1) -
                    selected.count { s -> s.constellation == it.constellation }
            } ?: break
            selected += next
        }
        if (selected.size < minimumSatellites)
            diagnostics += "卫星下限 $minimumSatellites 无法满足，当前有效池仅 ${pool.size} 颗"
        val usesReplay = selected.any(::statistical)
        if (usesReplay) {
            diagnostics += "混合来源：其余卫星保留统计回放几何；星座比例会随 GPS 可见性变化"
            diagnostics += base.diagnostics.map { "统计分量：$it" }
        }
        val geometry = GnssGeometry.solve(selected)
        geometry.reason?.let { diagnostics += "无有效三维定位解：$it" }
        val output = selected.sortedWith(compareBy({ it.constellation.ordinal }, { it.svid })).map {
            if (geometry.dop == null) it.copy(usedInFix = false) else it
        }
        return ReceiverGnssFrame(atMillis, observer, altitudeMeters, output, geometry,
            if (usesReplay) ReceiverGnssSource.HYBRID_BROADCAST_REPLAY else ReceiverGnssSource.BROADCAST_EPHEMERIS,
            diagnostics, pool.size, minimumSatellites)
    }
}
