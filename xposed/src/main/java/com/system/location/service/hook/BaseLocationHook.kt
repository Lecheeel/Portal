package com.system.location.service.hook

import com.system.location.service.hook.utils.toLocation

import android.location.Location
import android.location.LocationManager
import com.system.location.service.core.location.LocationSample
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.hook.utils.ProviderReadContext
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
        context: ProviderReadContext = ProviderView.capture()): Location =
        sampleForProvider(provider, scenarioId, point, context).toLocation(provider,
            GnssFrameRoute.forObserver(scenarioId, point), context.state, context.validationAtMillis)

    fun injectLocation(originLocation: Location, realLocation: Boolean = true,
        context: ProviderReadContext? = null): Location {
        if (!(context?.state ?: FakeLoc.runtimeSnapshots.current()).configuration.enabled) {
            if (realLocation && originLocation.provider == LocationManager.GPS_PROVIDER) {
                FakeLoc.lastLocation = originLocation
            }
            return originLocation
        }
        val captured = context ?: ProviderView.capture()
        if (!captured.state.configuration.enabled) return originLocation
        val provider = if (captured.state.configuration.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            LocationManager.GPS_PROVIDER
        } else originLocation.provider
        return locationForProvider(provider, context = captured)
    }

}
