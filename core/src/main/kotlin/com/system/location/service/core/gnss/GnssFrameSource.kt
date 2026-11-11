package com.system.location.service.core.gnss

import com.system.location.service.core.location.LocationSample
import java.util.concurrent.atomic.AtomicLong
import java.lang.ref.WeakReference

/** Process-local immutable source handle. Runtime IPC carries data and policy, not frame caches. */
interface GnssFrameSource {
    val requiresBootIdentity: Boolean
    fun frame(sample: LocationSample, minimumSatellites: Int, route: GnssFrameRoute,
        validationAtMillis: Long, currentBootCount: Int? = null): PublishedGnssFrame?
}

internal object GnssPublicationSequences { val counter = AtomicLong() }

class AutonomousGnssSource internal constructor(policy: GnssSimulationPolicy,
    navigation: RinexNavigationResource? = null, calibration: GnssAutonomousSignalCalibration? = null) : GnssFrameSource {
    override val requiresBootIdentity = false
    private val frames = GnssFrameStore(GnssPublicationSequences.counter).apply {
        configureAutonomous(GnssBundledOrbitResources.model(policy,
            // Legacy arrays/NMEA and Xiaomi Android 14 Builder/independent-receiver coverage.
            // Other platform/vendor receiver limits still need their own device validation.
            GnssOutputLimits(maxVisible = 128, maxPerTalker = 36, maxUsed = 32),
            // GPS-only application budget for approximate sky geometry, not an accuracy claim.
            gpsAlmanacAgeBudgetSeconds = 7 * 86400.0, navigation = navigation, signalCalibration = calibration))
    }

    override fun frame(sample: LocationSample, minimumSatellites: Int, route: GnssFrameRoute,
        validationAtMillis: Long, currentBootCount: Int?): PublishedGnssFrame? =
        frames.current(sample.timeMillis, sample.coordinate, sample.altitude, minimumSatellites,
            sample.speed.toDouble(), sample.bearing.toDouble(), validationAtMillis, route,
            sample.elapsedNanos, currentBootCount)
}

/**
 * Live immutable configurations own their source handles. The registry only interns sources:
 * it must not pin up to 64 obsolete navigation resources and their 4096-publication caches.
 * Old configuration readers still retain their original publications through their own handles.
 */
object GnssSourceSelection {
    private data class Key(val policy: GnssSimulationPolicy, val navigationDigest: String?, val navigationSource: String?,
        val captureDigest: String?)
    private val autonomous = object : LinkedHashMap<Key, WeakReference<AutonomousGnssSource>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, WeakReference<AutonomousGnssSource>>?) = size > 64
    }

    @Synchronized
    fun source(policy: GnssSimulationPolicy, capture: CapturedGnssSource?,
        navigation: RinexNavigationResource? = null): GnssFrameSource? = when (policy.mode) {
        GnssSimulationMode.OFF -> null
        GnssSimulationMode.REPLAY -> capture
        GnssSimulationMode.AUTO, GnssSimulationMode.AUTONOMOUS ->
            // Validate before key reuse: an asserted digest is not proof that bytes match.
            navigation.let { resource ->
                resource?.validate()
                val signalSource = capture.takeIf { policy.mode == GnssSimulationMode.AUTO }
                val key = Key(policy, resource?.sha256, resource?.sourceUrl, signalSource?.digest)
                autonomous[key]?.get() ?: AutonomousGnssSource(policy, resource, signalSource?.signalCalibration)
                    .also { autonomous[key] = WeakReference(it) }
            }
    }
}
