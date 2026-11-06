package com.system.location.service.hook.utils

import android.location.LocationManager
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.playback.NoiseProfile
import com.system.location.service.core.playback.PhysicalNoise
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.providers.ProviderMixer

/** Provider-specific view of the current fake fix; shared by all injection paths (P4-03). */
object ProviderView {
    private val mixer = ProviderMixer()
    private var noise: PhysicalNoise? = null
    private var noiseSeed: Long? = null

    fun sampleFor(provider: String?, scenarioId: String? = null, point: com.system.location.service.core.geo.Wgs84? = null): LocationSample {
        val base = if (point != null) {
            FakeLoc.snapshotForScenario(null).copy(coordinate = point, speed = 0f, bearing = 0f)
        } else {
            FakeLoc.snapshotForScenario(scenarioId)
        }
        val sample = applyNoise(base)
        val kind = when (provider) {
            LocationManager.GPS_PROVIDER, "GnssBatch" -> ProviderKind.GPS
            LocationManager.NETWORK_PROVIDER -> ProviderKind.NETWORK
            "fused" -> ProviderKind.FUSED
            else -> return sample
        }
        return mixer.mix(sample)[kind]?.sample ?: sample
    }

    private fun applyNoise(sample: LocationSample): LocationSample {
        if (!FakeLoc.enablePhysicalNoise) return sample
        synchronized(this) {
            if (noise == null || noiseSeed != FakeLoc.physicalNoiseSeed) {
                noise = PhysicalNoise(NoiseProfile(enabled = true, seed = FakeLoc.physicalNoiseSeed))
                noiseSeed = FakeLoc.physicalNoiseSeed
            }
            return noise!!.apply(sample, sample.elapsedNanos)
        }
    }
}
