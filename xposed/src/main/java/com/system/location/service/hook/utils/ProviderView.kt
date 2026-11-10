package com.system.location.service.hook.utils

import android.location.LocationManager
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.playback.NoiseProfile
import com.system.location.service.core.playback.PhysicalNoise
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.providers.ProviderMixer
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.geo.Wgs84

/** One delivery's immutable configuration and leased samples, with provider views memoized locally. */
class ProviderReadContext internal constructor(
    val state: RuntimeSnapshot,
    scenarios: Map<String, LocationSample>,
    val validationAtMillis: Long,
) {
    private val scenarios = scenarios.toMap()
    private val views = mutableMapOf<Pair<String?, GnssFrameRoute>, LocationSample>()
    init { require(state.sample != null) }

    internal fun base(route: GnssFrameRoute): LocationSample = when (route) {
        GnssFrameRoute.Global -> state.sample!!
        is GnssFrameRoute.FixedPoint -> state.sample!!.copy(coordinate = route.point, speed = 0f, bearing = 0f)
        is GnssFrameRoute.Scenario -> scenarios[route.id] ?: state.sample!!
    }

    @Synchronized
    internal fun view(provider: String?, route: GnssFrameRoute, create: () -> LocationSample): LocationSample =
        views.getOrPut(provider to route, create)
}

/** Provider-specific view of the current fake fix; shared by all injection paths (P4-03). */
object ProviderView {
    private val mixer = ProviderMixer()
    private var noise: PhysicalNoise? = null
    private var noiseSeed: Long? = null

    fun capture(now: Long = android.os.SystemClock.elapsedRealtimeNanos(),
        timeMillis: Long = System.currentTimeMillis()): ProviderReadContext = ProviderReadContext(
        FakeLoc.snapshotState(now = now, timeMillis = timeMillis), FakeLoc.scenarioSamples.snapshot(now), timeMillis)

    fun sampleFor(provider: String?, scenarioId: String? = null, point: Wgs84? = null,
        context: ProviderReadContext = capture()): LocationSample {
        val route = when {
            point != null -> GnssFrameRoute.FixedPoint(point)
            scenarioId != null -> GnssFrameRoute.Scenario(scenarioId)
            else -> GnssFrameRoute.Global
        }
        return context.view(provider, route) { mix(provider, applyNoise(context.base(route), context.state)) }
    }

    private fun mix(provider: String?, sample: LocationSample): LocationSample {
        val kind = when (provider) {
            LocationManager.GPS_PROVIDER, "GnssBatch" -> ProviderKind.GPS
            LocationManager.NETWORK_PROVIDER -> ProviderKind.NETWORK
            "fused" -> ProviderKind.FUSED
            else -> return sample
        }
        return mixer.mix(sample)[kind]?.sample ?: sample
    }

    private fun applyNoise(sample: LocationSample, state: RuntimeSnapshot): LocationSample {
        if (!state.physicalNoise) return sample
        synchronized(this) {
            if (noise == null || noiseSeed != state.physicalNoiseSeed) {
                noise = PhysicalNoise(NoiseProfile(enabled = true, seed = state.physicalNoiseSeed))
                noiseSeed = state.physicalNoiseSeed
            }
            return noise!!.apply(sample, sample.elapsedNanos)
        }
    }
}
