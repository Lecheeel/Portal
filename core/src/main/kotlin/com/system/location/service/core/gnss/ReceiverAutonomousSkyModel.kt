package com.system.location.service.core.gnss

/**
 * Whole-frame target sky. No capture, replay clock or capture expiry participates in geometry.
 * Physical resources are resolved per identity. Only an unavailable orbital pool selects the
 * virtual world; an individually hidden real satellite is never moved above the horizon.
 */
class ReceiverAutonomousSkyModel(
    providers: List<GnssOrbitProvider>,
    private val virtualFallback: GnssOrbitProvider,
    private val policy: GnssSimulationPolicy,
    /** Steady simulated navigation knowledge; never a claim to emit raw navigation messages. */
    private val simulateNavigation: Boolean = true,
    private val quantitySupplement: GnssVirtualSkySupplement? = null,
    private val signalCalibration: GnssAutonomousSignalCalibration? = null,
) {
    private val resolver = GnssOrbitResolver(providers)
    private val receiver = GnssSimulatedReceiver(policy.virtualSeed)

    init {
        require(providers.none { it.kind == GnssOrbitSourceKind.VIRTUAL })
        require(virtualFallback.kind == GnssOrbitSourceKind.VIRTUAL)
    }

    fun frame(request: GnssSkyRequest, minimumSatellites: Int = 0,
        validationAtMillis: Long = request.atMillis): ReceiverGnssFrame? {
        require(minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        if (policy.mode == GnssSimulationMode.OFF || policy.mode == GnssSimulationMode.REPLAY) return null
        val resolved = resolver.positions(request)
        val batch = if (resolved.positions.isEmpty()) {
            val fallback = virtualFallback.positions(request)
            // Whole-frame fallback must not erase real negative/hidden identity reservations.
            // Otherwise all-unhealthy or expired resources revive their IDs as virtual satellites,
            // and quantity supplementation can claim the same reserved identities again.
            val allowed = fallback.positions.filter { (it.constellation to it.svid) !in resolved.reservedIdentities }
            val excluded = fallback.positions.size - allowed.size
            GnssOrbitBatch(allowed,
                fallback.diagnostics + if (excluded > 0) listOf("虚拟兜底避让 $excluded 个真实资源保留身份") else emptyList(),
                fallback.reservedIdentities + resolved.reservedIdentities,
                fallback.blockedIdentities + resolved.blockedIdentities)
        } else resolved
        val diagnostics = resolved.diagnostics.toMutableList()
        val calibration = signalCalibration?.takeIf { policy.mode == GnssSimulationMode.AUTO && it.isCurrent(validationAtMillis) }
        if (calibration != null) diagnostics += "AUTO 迁移采集信号环境：仅匹配频段与观测仰角范围；不代表目标地实测接收情况"
        else if (signalCalibration != null) diagnostics += "采集信号校准未启用或已过期，继续自主信号模型"
        if (batch !== resolved) diagnostics += batch.diagnostics
        fun observe(orbit: GnssOrbitPosition): Pair<GnssOrbitPosition, GnssReceiverSignal>? {
            val navigation = orbit.simulatedNavigation.takeIf { simulateNavigation && !orbit.hasEphemeris }
            return receiver.observe(orbit, request, policy.environment, navigation, calibration)?.let { orbit to it }
        }
        val observations = batch.positions.mapNotNull(::observe).toMutableList()
        quantitySupplement?.outputLimits?.let { limits ->
            val talkerCounts = observations.groupingBy {
                if (it.first.constellation == Constellation.SBAS) Constellation.GPS else it.first.constellation
            }.eachCount()
            if (observations.size > limits.maxVisible || talkerCounts.values.any { it > limits.maxPerTalker })
                diagnostics += "原始天空超出已验证输出能力（总数 ${limits.maxVisible}，每 talker ${limits.maxPerTalker}）；保留完整天空，输出端兼容性未保证"
        }
        var capacity = observations.size
        if (policy.minimumPolicy == GnssMinimumPolicy.GUARANTEE_QUANTITY && quantitySupplement != null) {
            val addition = quantitySupplement.supplement(request, observations.map { it.first },
                batch.reservedIdentities, minimumSatellites)
            capacity = addition.availableCapacity
            diagnostics += addition.diagnostics
            observations += addition.positions.mapNotNull(::observe)
        }
        if (observations.size < minimumSatellites)
            diagnostics += "卫星下限 $minimumSatellites 未满足，当前可见 ${observations.size} 颗；策略 ${policy.minimumPolicy}"
        // GGA's used-count field is at most two digits. A visible floor never forces every
        // visible satellite into the navigation solution or silently changes its geometry.
        val maximumUsed = quantitySupplement?.outputLimits?.maxUsed ?: 99
        val used = observations.map { it.second.satellite }.filter { it.usedInFix }
            .sortedByDescending { it.cn0DbHz }.take(maximumUsed).map { it.constellation to it.svid }.toSet()
        val satellites = observations.map { it.second.satellite.let { satellite ->
            satellite.copy(usedInFix = (satellite.constellation to satellite.svid) in used)
        } }
        val geometry = GnssGeometry.solve(satellites)
        geometry.reason?.let { diagnostics += "无有效三维定位解：$it" }
        val output = if (geometry.dop == null) satellites.map { it.copy(usedInFix = false) } else satellites
        val simulatedNavigationCount = observations.count { it.second.simulatedNavigation }
        if (simulatedNavigationCount > 0)
            diagnostics += "${simulatedNavigationCount} 颗使用显式仿真导航状态，不代表接收到广播导航电文"
        diagnostics += "模拟环境：${policy.environment}；可见 ${output.size}，跟踪 ${observations.count { it.second.tracked }}，在用 ${output.count { it.usedInFix }}"
        val origins = observations.associate { (orbit, _) -> (orbit.constellation to orbit.svid) to orbit.provenance }
        return ReceiverGnssFrame(request.atMillis, request.observer, request.altitudeMeters, output,
            geometry, ReceiverGnssSource.AUTONOMOUS, diagnostics.toList(), capacity, minimumSatellites,
            orbitProvenance = origins, trackedSatelliteCount = observations.count { it.second.tracked },
            simulatedNavigationSatelliteCount = simulatedNavigationCount)
    }
}
