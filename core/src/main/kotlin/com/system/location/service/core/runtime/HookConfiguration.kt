package com.system.location.service.core.runtime

import com.system.location.service.core.gnss.CapturedGnssSource
import com.system.location.service.core.gnss.GnssFrame
import com.system.location.service.core.gnss.GnssSimulationPolicy
import com.system.location.service.core.gnss.GnssSourceSelection
import com.system.location.service.core.gnss.GnssFrameSource
import com.system.location.service.core.gnss.RinexNavigationResource
import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.sensors.StepSimulationSettings
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
    val disableNetworkLocation: Boolean = false,
    val disableRequestGeofence: Boolean = false,
    val disableGetFromLocation: Boolean = false,
    val downgradeToCdma: Boolean = false,
    val nmeaEnabled: Boolean = true,
    val hideMock: Boolean = true,
    /** Master switch for SIM/operator identity; saved profiles are retained while this is off. */
    val carrierIdentityEnabled: Boolean = false,
    val carrierIdentity: CarrierIdentityConfig? = null,
    /** Experimental native path rewriting; default off and only applied to explicit targets. */
    val antiDetectEnabled: Boolean = false,
    /** Experimental global stack-frame filtering; default off, changes observable stack length. */
    val stackTraceCleanEnabled: Boolean = false,
    /**
     * Experimental stack-clean strategy switch: when [stackTraceCleanEnabled] is on, rewrite
     * module frames in place instead of removing them, keeping the reported array length
     * consistent with the native depth count. Length parity is not invisibility; see
     * docs/anti-detection.md for the known boundaries.
     */
    val stackTraceReplaceEnabled: Boolean = false,
    val minimumSatellites: Int = GnssSimulationPolicy.DEFAULT_MINIMUM_SATELLITES,
    val gnssSimulation: GnssSimulationPolicy = GnssSimulationPolicy(),
    val reportIntervalMs: Long = 100L,
    val sensorCadenceScale: Double = 1.0,
    val sensorVariation: Double = 0.15,
    val sensorTargets: Set<String> = emptySet(),
    val stepSimulation: StepSimulationSettings = StepSimulationSettings(),
    val wifiFingerprintEnabled: Boolean = false,
    val wifiFingerprintCatalogJson: String? = null,
    /** Optional receiver evidence failed decoding; other configuration remains usable. */
    val gnssCaptureRejected: Boolean = false,
    @Transient val gnssSource: CapturedGnssSource? = null,
    @Transient val gnssNavigation: RinexNavigationResource? = null,
) {
    // Delegated process-local state is excluded from serialization and data-class equality.
    private val selectedGnssSource: GnssFrameSource? by lazy {
        GnssSourceSelection.source(gnssSimulation, gnssSource, gnssNavigation)
    }

    fun gnssFrameSource(): GnssFrameSource? = selectedGnssSource

    init {
        require(minimumSatellites in 0..GnssFrame.MAX_SATELLITES)
        require(reportIntervalMs in 50..1000)
        require(sensorCadenceScale in 0.5..2.0 && sensorVariation in 0.0..0.5)
        com.system.location.service.core.sensors.SensorTargets.validate(sensorTargets)
    }
}
