package com.system.location.service.hook.hooks.gnss

import android.provider.Settings
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.CapturedGnssSource
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.hook.utils.ProviderReadContext
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.RuntimeSnapshot

/** Resolves authenticated runtime policy to one shared autonomous or replay publication. */
object GnssReceiverRuntime {
    /** A callback evaluates all policy and evidence against this one captured context. */
    fun activeContext(): ProviderReadContext? {
        val initial = FakeLoc.runtimeSnapshots.current().configuration
        if (!initial.enabled || !initial.gnssEnabled || initial.gnssFrameSource() == null) return null
        val context = ProviderView.capture()
        val policy = context.state.configuration
        return context.takeIf { policy.enabled && policy.gnssEnabled && policy.gnssFrameSource() != null }
    }

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
        if (FakeLoc.runtimeSnapshots.current().configuration.gnssFrameSource() == null) return null
        return frameForRoute(route, ProviderView.capture())
    }

    fun frameForRoute(route: GnssFrameRoute, context: ProviderReadContext): PublishedGnssFrame? {
        if (context.state.configuration.gnssFrameSource() == null) return null
        val sample = when (route) {
            GnssFrameRoute.Global -> ProviderView.sampleFor("gps", context = context)
            is GnssFrameRoute.FixedPoint -> ProviderView.sampleFor("gps", point = route.point, context = context)
            is GnssFrameRoute.Scenario -> ProviderView.sampleFor("gps", scenarioId = route.id, context = context)
        }
        return frameForSample(sample, route, context.state, context.validationAtMillis)
    }

    fun frameForSample(sample: LocationSample, route: GnssFrameRoute = GnssFrameRoute.Global,
        state: RuntimeSnapshot = FakeLoc.runtimeSnapshots.current(),
        validationAtMillis: Long = System.currentTimeMillis(),
        currentBootCount: Int? = if (state.configuration.gnssFrameSource()?.requiresBootIdentity == true)
            readBootCount() else null): PublishedGnssFrame? =
        state.configuration.gnssFrameSource()?.frame(sample, state.configuration.minimumSatellites, route,
            validationAtMillis, currentBootCount)

    private fun readBootCount(): Int? = runCatching {
        val resolver = BinderUtils.getSystemContext()?.contentResolver ?: return null
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 }
    }.getOrNull()
}
