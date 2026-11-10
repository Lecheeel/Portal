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
    private val broadcastSignal = if (capture.frames.none { it.signalObservations.isNotEmpty() } &&
        capture.broadcastSignalCalibration != null) capture.broadcastSignalCalibration else BroadcastSignalCalibration.fit(capture)
    private val evidence = if (capture.measurements.isNotEmpty() || capture.navigation.isNotEmpty())
        GpsBroadcastEvidence.fromCapture(capture) else capture.gpsBroadcastEvidence
    private val galileoEvidence = capture.galileoBroadcastEvidence
    private val beiDouEvidence = capture.beiDouBroadcastEvidence
    private val glonassEvidence = capture.glonassBroadcastEvidence
    internal val broadcastBootCount: Int?
        get() = evidence?.clock?.bootCount ?: galileoEvidence?.clock?.bootCount
            ?: beiDouEvidence?.clock?.bootCount ?: glonassEvidence?.clock?.bootCount
    private val ratios = capture.profile().constellations.associate { it.constellation to it.ratio }

    fun frame(atMillis: Long, observer: Wgs84, altitudeMeters: Double, minimumSatellites: Int = 0,
        elapsedRealtimeNanos: Long? = null, currentBootCount: Int? = null): ReceiverGnssFrame? {
        require(atMillis >= 0 && altitudeMeters.isFinite())
        require(minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        if (evidence == null && galileoEvidence == null && beiDouEvidence == null && glonassEvidence == null)
            return replay.frame(atMillis, observer, altitudeMeters, minimumSatellites)
        val time = evidence?.let { e -> elapsedRealtimeNanos?.let { e.clock.at(it, currentBootCount) } }
        val galileoTime = galileoEvidence?.let { e ->
            elapsedRealtimeNanos?.let { elapsed ->
                e.clock.at(elapsed, currentBootCount)?.let { gps -> e.gpsToGst.gpsToGalileo(gps).time }
            }
        }
        val beiDouTime = beiDouEvidence?.let { e -> elapsedRealtimeNanos?.let { e.clock.at(it, currentBootCount) } }
        val glonassDomain = glonassEvidence?.clock?.domain()
        val glonassTime = glonassEvidence?.let { e ->
            elapsedRealtimeNanos?.let { e.clock.at(it, currentBootCount, glonassDomain!!) }
        }
        val base = replay.selection(atMillis, 0) ?: return null
        val diagnostics = mutableListOf<String>()
        if (evidence != null) diagnostics += "GPS 方位/仰角来自广播轨道和真空光程；CN0、在用候选来自本次采集的经验校准，非实时接收状态"
        if (galileoEvidence != null) diagnostics += "Galileo 方位/仰角来自经 GGTO 对齐的广播轨道；坐标系和时标证据均需独立建立"
        if (beiDouEvidence != null) diagnostics += "北斗方位/仰角来自 BDT 广播轨道；BDCS 坐标和服务有效期均来自显式证据"
        if (evidence != null && time == null) diagnostics += "GPS 广播几何未启用：缺少同次开机的有效单调时间，当前明确使用统计回放"
        if (galileoEvidence != null && galileoTime == null) diagnostics += "Galileo 广播几何未启用：缺少同次开机的有效 GPS/GST 时标，当前明确使用统计回放"
        if (beiDouEvidence != null && beiDouTime == null) diagnostics += "北斗广播几何未启用：缺少同次开机的有效 BDT 时标，当前明确使用统计回放"
        if (glonassEvidence != null && glonassTime == null) diagnostics += "GLONASS 广播几何未启用：缺少同次开机的 UTC(SU)+3h 连续时标，当前明确使用统计回放"
        val broadcastIds = evidence?.ephemerides?.map { it.ephemeris.svid }?.toSet().orEmpty()
        val galileoIds = galileoEvidence?.identities.orEmpty()
        val beiDouIds = beiDouEvidence?.identities.orEmpty()
        val glonassIds = glonassEvidence?.identities.orEmpty()
        fun statistical(s: GnssSatellite) = when (s.constellation) {
            Constellation.GPS -> s.svid !in broadcastIds
            Constellation.GALILEO -> s.svid !in galileoIds
            Constellation.BEIDOU -> s.svid !in beiDouIds
            Constellation.GLONASS -> s.svid !in glonassIds
            else -> true
        }
        val physicalGps = if (evidence != null && time != null) evidence.ephemerides.mapNotNull { decoded ->
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
                azimuth.toGnssAzimuthFloat(), calibration.cn0DbHz.toFloat(),
                calibration.usedFraction >= 0.5 && calibration.cn0DbHz > 0.0,
                hasEphemeris = true, hasAlmanac = calibration.hasAlmanac, carrierHz = calibration.carrierHz)
        }.sortedBy { it.svid } else emptyList()
        val galileoReceiver = galileoEvidence?.receiverTransform
            ?.receiver(observer, altitudeMeters, elapsedRealtimeNanos, currentBootCount)
            ?.let { GtrfReceiver(it.position, it.orientation) }
        if (galileoEvidence != null && galileoReceiver == null)
            diagnostics += "Galileo 广播几何未启用：缺少适用的 WGS 84 → GTRF 接收机变换，未回退同身份统计几何"
        val physicalGalileo = if (galileoEvidence != null && galileoTime != null &&
            galileoEvidence.receiverFrame == GalileoReceiverFrame.GTRF && galileoReceiver != null) {
            val inav = galileoEvidence.inav.mapNotNull { decoded ->
                val result = GalileoReceptionGeometry.observe(decoded.ephemeris, galileoTime, galileoReceiver)
                val los = result.observation?.lineOfSight
                if (los == null || !los.aboveHorizon || los.azimuthDegrees == null) {
                    diagnostics += "Galileo ${decoded.ephemeris.svid}：${result.reason ?: "地平线以下或方位未定义"}"
                    return@mapNotNull null
                }
                val calibration = listOf(GalileoOpenServiceSignal.E1 to 1_575_420_000.0,
                    GalileoOpenServiceSignal.E5B to 1_207_140_000.0).firstNotNullOfOrNull { (band, frequency) ->
                    if (GalileoSignalClock.correction(decoded, result.observation.transmissionTime, band).correctionSeconds == null)
                        null else broadcastSignal.at(Constellation.GALILEO, los.elevationDegrees, frequency)
                } ?: run {
                    diagnostics += "Galileo ${decoded.ephemeris.svid}：没有健康、数据有效且具备同频实测校准的 I/NAV 信号"
                    return@mapNotNull null
                }
                GnssSatellite(Constellation.GALILEO, decoded.ephemeris.svid, los.elevationDegrees.toFloat(),
                    los.azimuthDegrees.toGnssAzimuthFloat(), calibration.cn0.toFloat(), calibration.used >= 0.5 && calibration.cn0 > 0,
                    true, calibration.almanac, calibration.carrier)
            }
            val fnav = galileoEvidence.fnav.mapNotNull { decoded ->
                val result = GalileoReceptionGeometry.observe(decoded.ephemeris, galileoTime, galileoReceiver)
                val los = result.observation?.lineOfSight
                if (los == null || !los.aboveHorizon || los.azimuthDegrees == null) {
                    diagnostics += "Galileo ${decoded.ephemeris.svid}：${result.reason ?: "地平线以下或方位未定义"}"
                    return@mapNotNull null
                }
                val correction = GalileoSignalClock.correction(decoded, result.observation.transmissionTime,
                    GalileoOpenServiceSignal.E5A)
                if (correction.correctionSeconds == null) {
                    diagnostics += "Galileo ${decoded.ephemeris.svid}：${correction.reason}"
                    return@mapNotNull null
                }
                val calibration = broadcastSignal.at(Constellation.GALILEO, los.elevationDegrees, 1_176_450_000.0)
                    ?: run { diagnostics += "Galileo ${decoded.ephemeris.svid}：缺少 E5a 同频实测校准"; return@mapNotNull null }
                GnssSatellite(Constellation.GALILEO, decoded.ephemeris.svid, los.elevationDegrees.toFloat(),
                    los.azimuthDegrees.toGnssAzimuthFloat(), calibration.cn0.toFloat(), calibration.used >= 0.5 && calibration.cn0 > 0,
                    true, calibration.almanac, calibration.carrier)
            }
            (inav + fnav).distinctBy { it.constellation to it.svid }
        } else emptyList()
        if (galileoEvidence != null && galileoEvidence.receiverFrame != GalileoReceiverFrame.GTRF)
            diagnostics += "Galileo 广播证据坐标系不受支持，未输出物理几何"
        val receiverBdcs = beiDouEvidence?.receiverTransform
            ?.receiver(observer, altitudeMeters, elapsedRealtimeNanos, currentBootCount)
            ?.let { BdcsReceiver(it.position, it.orientation) }
        if (beiDouEvidence != null && receiverBdcs == null)
            diagnostics += "北斗广播几何未启用：缺少适用的 WGS 84 → BDCS 接收机变换，未回退同身份统计几何"
        val physicalBeiDou = if (beiDouEvidence != null && beiDouTime != null &&
            beiDouEvidence.receiverFrame == BeiDouReceiverFrame.BDCS && receiverBdcs != null) {
            (beiDouEvidence.d1.mapNotNull { decoded ->
                val result = BeiDouSignalReception.observe(decoded, beiDouTime, receiverBdcs,
                    BeiDouLegacySignal.B1I, beiDouEvidence.serviceValidity)
                val los = result.observation?.geometry?.lineOfSight
                if (los == null || !los.aboveHorizon || los.azimuthDegrees == null) {
                    diagnostics += "北斗 ${decoded.ephemeris.svid}：${result.reason ?: "地平线以下或方位未定义"}"
                    return@mapNotNull null
                }
                val calibration = broadcastSignal.at(Constellation.BEIDOU, los.elevationDegrees, 1_561_098_000.0)
                    ?: run { diagnostics += "北斗 ${decoded.ephemeris.svid}：仰角超出实测校准范围，未外推 CN0"; return@mapNotNull null }
                GnssSatellite(Constellation.BEIDOU, decoded.ephemeris.svid, los.elevationDegrees.toFloat(),
                    los.azimuthDegrees.toGnssAzimuthFloat(), calibration.cn0.toFloat(), calibration.used >= 0.5 && calibration.cn0 > 0,
                    true, calibration.almanac, calibration.carrier)
            } + beiDouEvidence.d2.mapNotNull { decoded ->
                val result = BeiDouSignalReception.observe(decoded, beiDouTime, receiverBdcs,
                    BeiDouLegacySignal.B1I, beiDouEvidence.serviceValidity)
                val los = result.observation?.geometry?.lineOfSight
                if (los == null || !los.aboveHorizon || los.azimuthDegrees == null) {
                    diagnostics += "北斗 ${decoded.ephemeris.svid}：${result.reason ?: "地平线以下或方位未定义"}"
                    return@mapNotNull null
                }
                val calibration = broadcastSignal.at(Constellation.BEIDOU, los.elevationDegrees, 1_561_098_000.0)
                    ?: run { diagnostics += "北斗 ${decoded.ephemeris.svid}：仰角超出实测校准范围，未外推 CN0"; return@mapNotNull null }
                GnssSatellite(Constellation.BEIDOU, decoded.ephemeris.svid, los.elevationDegrees.toFloat(),
                    los.azimuthDegrees.toGnssAzimuthFloat(), calibration.cn0.toFloat(), calibration.used >= 0.5 && calibration.cn0 > 0,
                    true, calibration.almanac, calibration.carrier)
            }).distinctBy { it.constellation to it.svid }
        } else emptyList()
        if (beiDouEvidence != null && beiDouEvidence.receiverFrame != BeiDouReceiverFrame.BDCS)
            diagnostics += "北斗广播证据坐标系不受支持，未输出物理几何"
        val glonassReceiver = glonassEvidence?.datumTransform
            ?.receiver(observer, altitudeMeters, elapsedRealtimeNanos, currentBootCount)
        if (glonassEvidence != null && glonassReceiver == null)
            diagnostics += "GLONASS 广播几何未启用：缺少适用的 WGS 84 → PZ-90 接收机变换，未回退同身份统计几何"
        val physicalGlonass = if (glonassEvidence != null && glonassTime != null && glonassReceiver != null) {
            val receiver = GlonassReceiver(glonassEvidence.receiverFrame, glonassReceiver.position,
                glonassReceiver.orientation)
            val domain = glonassDomain!!
            glonassEvidence.ephemerides.mapNotNull { encoded ->
                val ephemeris = encoded.resolve(domain)
                if (ephemeris == null) {
                    diagnostics += "GLONASS ${encoded.orbit.slot}：广播有效期不在独立时钟覆盖内"
                    return@mapNotNull null
                }
                val result = GlonassReceptionGeometry.observe(ephemeris, glonassTime, receiver)
                result.observation?.let { observation ->
                    encoded.serviceRejection(observation.transmissionTime)?.let { reason ->
                        diagnostics += "GLONASS ${encoded.orbit.slot}：$reason，未回退同身份统计几何"
                        return@mapNotNull null
                    }
                }
                val los = result.observation?.lineOfSight
                if (los == null || !los.aboveHorizon || los.azimuthDegrees == null) {
                    diagnostics += "GLONASS ${encoded.orbit.slot}：${result.reason ?: "地平线以下或方位未定义"}"
                    return@mapNotNull null
                }
                val calibration = broadcastSignal.at(Constellation.GLONASS, los.elevationDegrees, svid = encoded.orbit.slot)
                    ?: run { diagnostics += "GLONASS ${encoded.orbit.slot}：仰角超出实测校准范围，未外推 CN0"; return@mapNotNull null }
                GnssSatellite(Constellation.GLONASS, encoded.orbit.slot, los.elevationDegrees.toFloat(),
                    los.azimuthDegrees.toGnssAzimuthFloat(), calibration.cn0.toFloat(), calibration.used >= 0.5 && calibration.cn0 > 0,
                    true, calibration.almanac, calibration.carrier)
            }
        } else emptyList()
        if (glonassEvidence != null && glonassTime == null)
            diagnostics += "GLONASS 广播证据保留但未生成几何：时钟域不完整或已过期"
        // All genuinely observed, valid broadcast identities may contribute, including those
        // absent from the current statistical window. The remaining pool stays local empirical.
        val physical = physicalGps + physicalGalileo + physicalBeiDou + physicalGlonass
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
        val hasPhysical = physical.isNotEmpty()
        val source = when {
            !hasPhysical -> ReceiverGnssSource.STATISTICAL_REPLAY
            usesReplay -> ReceiverGnssSource.HYBRID_BROADCAST_REPLAY
            else -> ReceiverGnssSource.BROADCAST_EPHEMERIS
        }
        return ReceiverGnssFrame(atMillis, observer, altitudeMeters, output, geometry,
            source,
            diagnostics, pool.size, minimumSatellites)
    }

}
