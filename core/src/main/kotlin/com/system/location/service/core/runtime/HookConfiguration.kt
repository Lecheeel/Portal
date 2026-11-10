package com.system.location.service.core.runtime

import com.system.location.service.core.gnss.CapturedGnssSource
import com.system.location.service.core.gnss.GnssFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Immutable Hook policy. Capture preparation has no side effects on the published configuration. */
@Serializable
data class HookConfiguration(
    val enabled: Boolean = false,
    val gnssEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val logEnabled: Boolean = true,
    val debugLog: Boolean = false,
    val disableGetCurrentLocation: Boolean = false,
    val disableRegisterLocationListener: Boolean = false,
    val disableFusedLocation: Boolean = false,
    val disableNetworkLocation: Boolean = true,
    val disableRequestGeofence: Boolean = false,
    val disableGetFromLocation: Boolean = false,
    val downgradeToCdma: Boolean = false,
    val allowPhysicalRawGnss: Boolean = false,
    val nmeaEnabled: Boolean = true,
    val hideMock: Boolean = true,
    val hookWifi: Boolean = true,
    val minimumSatellites: Int = 0,
    val reportIntervalMs: Long = 100L,
    val sensorCadenceScale: Double = 1.0,
    val sensorVariation: Double = 0.15,
    val wifiFingerprintEnabled: Boolean = false,
    val wifiFingerprintCatalogJson: String? = null,
    val speedAmplitude: Double = 1.0,
    @Transient val gnssSource: CapturedGnssSource? = null,
) {
    init {
        require(minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        require(reportIntervalMs in 50..1000)
        require(sensorCadenceScale in 0.5..2.0 && sensorVariation in 0.0..0.5)
        require(speedAmplitude in 0.0..10.0)
    }
}
