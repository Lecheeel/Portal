package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.CapturedGnssSource
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.RuntimeSnapshot

/** Configured only by the authenticated command channel. No catalog fallback exists here. */
object GnssReceiverRuntime {
    @Synchronized
    fun configure(payload: String?) {
        val prepared = CapturedGnssSource.prepare(payload, FakeLoc.runtimeSnapshots.current().configuration.gnssSource)
        FakeLoc.runtimeSnapshots.update { state ->
            if (state.configuration.gnssSource === prepared) state else state.copy(
                configRevision = Math.addExact(state.configRevision, 1),
                configuration = state.configuration.copy(gnssSource = prepared))
        }
    }

    fun currentFrame(point: Wgs84? = null): PublishedGnssFrame? {
        return frameForRoute(GnssFrameRoute.forObserver(point = point))
    }

    fun frameForRoute(route: GnssFrameRoute): PublishedGnssFrame? {
        val state = FakeLoc.runtimeSnapshots.current()
        if (state.configuration.gnssSource == null) return null
        val sample = when (route) {
            GnssFrameRoute.Global -> ProviderView.sampleFor("gps")
            is GnssFrameRoute.FixedPoint -> ProviderView.sampleFor("gps", point = route.point)
            is GnssFrameRoute.Scenario -> ProviderView.sampleFor("gps", scenarioId = route.id)
        }
        return frameForSample(sample, route, state)
    }

    fun frameForSample(sample: LocationSample, route: GnssFrameRoute = GnssFrameRoute.Global,
        state: RuntimeSnapshot = FakeLoc.runtimeSnapshots.current()): PublishedGnssFrame? =
        state.configuration.gnssSource?.frame(sample, state.configuration.minimumSatellites, route, System.currentTimeMillis())
}
