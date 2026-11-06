package com.system.location.service.hook.utils

import android.location.LocationManager
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.providers.ProviderMixer

/** Provider-specific view of the current fake fix; shared by all injection paths (P4-03). */
object ProviderView {
    private val mixer = ProviderMixer()

    fun sampleFor(provider: String?): LocationSample {
        val sample = FakeLoc.snapshot()
        val kind = when (provider) {
            LocationManager.GPS_PROVIDER, "GnssBatch" -> ProviderKind.GPS
            LocationManager.NETWORK_PROVIDER -> ProviderKind.NETWORK
            "fused" -> ProviderKind.FUSED
            else -> return sample
        }
        return mixer.mix(sample)[kind]?.sample ?: sample
    }
}
