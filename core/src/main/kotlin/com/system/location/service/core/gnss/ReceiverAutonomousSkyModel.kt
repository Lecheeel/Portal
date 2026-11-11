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
) {
    private val resolver = GnssOrbitResolver(providers)
    private val receiver = GnssSimulatedReceiver(policy.virtualSeed)

    init {
        require(providers.none { it.kind == GnssOrbitSourceKind.VIRTUAL })
        require(virtualFallback.kind == GnssOrbitSourceKind.VIRTUAL)
    }

    fun frame(request: GnssSkyRequest): ReceiverGnssFrame? {
        if (policy.mode == GnssSimulationMode.OFF || policy.mode == GnssSimulationMode.REPLAY) return null
        val resolved = resolver.positions(request)
        val batch = if (resolved.positions.isEmpty()) virtualFallback.positions(request) else resolved
        val diagnostics = resolved.diagnostics.toMutableList()
        if (batch !== resolved) diagnostics += batch.diagnostics
        val observations = batch.positions.mapNotNull { orbit ->
            val navigation = if (simulateNavigation && !orbit.hasEphemeris)
                GnssSimulatedNavigation(request.atMillis, orbit.constellation, orbit.svid, orbit.position) else null
            receiver.observe(orbit, request, policy.environment, navigation)?.let { orbit to it }
        }
        val satellites = observations.map { it.second.satellite }
        val geometry = GnssGeometry.solve(satellites)
        geometry.reason?.let { diagnostics += "无有效三维定位解：$it" }
        val output = if (geometry.dop == null) satellites.map { it.copy(usedInFix = false) } else satellites
        val simulatedNavigationCount = observations.count { it.second.simulatedNavigation }
        if (simulatedNavigationCount > 0)
            diagnostics += "${simulatedNavigationCount} 颗使用显式仿真导航状态，不代表接收到广播导航电文"
        diagnostics += "模拟环境：${policy.environment}；可见 ${output.size}，跟踪 ${observations.count { it.second.tracked }}，在用 ${output.count { it.usedInFix }}"
        val origins = observations.associate { (orbit, _) -> (orbit.constellation to orbit.svid) to orbit.provenance }
        return ReceiverGnssFrame(request.atMillis, request.observer, request.altitudeMeters, output,
            geometry, ReceiverGnssSource.AUTONOMOUS, diagnostics.toList(), output.size,
            orbitProvenance = origins, trackedSatelliteCount = observations.count { it.second.tracked },
            simulatedNavigationSatelliteCount = simulatedNavigationCount)
    }
}
