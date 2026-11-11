package com.system.location.service.core.gnss

import com.system.location.service.core.location.LocationSample
import java.util.concurrent.atomic.AtomicLong

/** Process-local immutable source handle. Runtime IPC carries data and policy, not frame caches. */
interface GnssFrameSource {
    val requiresBootIdentity: Boolean
    fun frame(sample: LocationSample, minimumSatellites: Int, route: GnssFrameRoute,
        validationAtMillis: Long, currentBootCount: Int? = null): PublishedGnssFrame?
}

internal object GnssPublicationSequences { val counter = AtomicLong() }

class AutonomousGnssSource internal constructor(policy: GnssSimulationPolicy) : GnssFrameSource {
    override val requiresBootIdentity = false
    private val frames = GnssFrameStore(GnssPublicationSequences.counter).apply {
        configureAutonomous(GnssBundledOrbitResources.model(policy,
            // Exercised through legacy status arrays and complete NMEA reconstruction.
            // Public GnssStatus.Builder and independent device receivers remain release gates.
            GnssOutputLimits(maxVisible = 128, maxPerTalker = 36, maxUsed = 32),
            // GPS-only application budget for approximate sky geometry, not an accuracy claim.
            gpsAlmanacAgeBudgetSeconds = 7 * 86400.0))
    }

    override fun frame(sample: LocationSample, minimumSatellites: Int, route: GnssFrameRoute,
        validationAtMillis: Long, currentBootCount: Int?): PublishedGnssFrame? =
        frames.current(sample.timeMillis, sample.coordinate, sample.altitude, minimumSatellites,
            sample.speed.toDouble(), sample.bearing.toDouble(), validationAtMillis, route,
            sample.elapsedNanos, currentBootCount)
}

/** Stable policy copies reuse publications; old configuration readers keep their original handle. */
object GnssSourceSelection {
    private val autonomous = object : LinkedHashMap<GnssSimulationPolicy, AutonomousGnssSource>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GnssSimulationPolicy, AutonomousGnssSource>?) = size > 64
    }

    @Synchronized
    fun source(policy: GnssSimulationPolicy, capture: CapturedGnssSource?): GnssFrameSource? = when (policy.mode) {
        GnssSimulationMode.OFF -> null
        GnssSimulationMode.REPLAY -> capture
        GnssSimulationMode.AUTO, GnssSimulationMode.AUTONOMOUS ->
            autonomous.getOrPut(policy) { AutonomousGnssSource(policy) }
    }
}
