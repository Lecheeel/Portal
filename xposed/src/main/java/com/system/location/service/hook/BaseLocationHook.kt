package com.system.location.service.hook

import com.system.location.service.hook.utils.toLocation

import android.location.Location
import android.location.LocationManager
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.location.LocationBatchTimeline
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.hook.utils.ProviderReadContext
import com.system.location.service.hook.runtime.CurrentLocationDelivery
import com.system.location.service.core.gnss.GnssFrameRoute

abstract class BaseLocationHook: BaseDivineService() {
    /** Provider-specific view of the current fake fix (P4-03): accuracy, latency, field mask. */
    fun sampleForProvider(
        provider: String?,
        scenarioId: String? = null,
        point: com.system.location.service.core.geo.Wgs84? = null,
        context: ProviderReadContext = ProviderView.capture(),
    ): LocationSample = ProviderView.sampleFor(provider, scenarioId, point, context)

    fun locationForProvider(provider: String?, scenarioId: String? = null,
        point: com.system.location.service.core.geo.Wgs84? = null,
        context: ProviderReadContext = ProviderView.capture()): Location {
        val view = ProviderView.viewFor(provider, scenarioId, point, context)
        return view.sample.toLocation(provider, GnssFrameRoute.forObserver(scenarioId, point),
            context.state, context.validationAtMillis, view.providerSample)
    }

    fun injectLocation(originLocation: Location, realLocation: Boolean = true,
        context: ProviderReadContext? = null): Location {
        // A current request already has a synthetic provider candidate. Preserve the platform's
        // subsequent coarse filtering and copies instead of replacing them with a fine fix again.
        if (CurrentLocationDelivery.active) return originLocation
        val captured = context ?: ProviderView.capture()
        if (!captured.state.configuration.enabled) {
            if (realLocation && originLocation.provider == LocationManager.GPS_PROVIDER) {
                FakeLoc.lastLocation = originLocation
            }
            return originLocation
        }
        val provider = if (captured.state.configuration.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            LocationManager.GPS_PROVIDER
        } else originLocation.provider
        return locationForProvider(provider, context = captured)
    }

    /** Replace a hardware batch from one provider snapshot; none of its measured fixes survive. */
    fun injectLocationBatch(
        originLocations: List<Location>,
        context: ProviderReadContext? = null,
    ): List<Location> {
        if (originLocations.isEmpty()) return emptyList()
        if (CurrentLocationDelivery.active) return originLocations
        val captured = context ?: ProviderView.capture()
        if (!captured.state.configuration.enabled) return originLocations
        val intervalMillis = captured.state.configuration.reportIntervalMs.coerceIn(1L, 60_000L)
        val provider = originLocations.last().provider
        val retainedCount = minOf(originLocations.size, LocationBatchTimeline.MAX_BATCH_SIZE)
        val endpoint = ProviderView.viewFor(provider, context = captured)
        return LocationBatchTimeline.synthesize(endpoint.sample, retainedCount, intervalMillis).map { sample ->
            sample.toLocation(provider, GnssFrameRoute.Global, captured.state, captured.validationAtMillis,
                endpoint.providerSample?.copy(sample = sample))
        }
    }

}
