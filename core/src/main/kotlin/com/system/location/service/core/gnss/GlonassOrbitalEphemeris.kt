package com.system.location.service.core.gnss

import kotlin.math.abs

/**
 * Orbital state plus independently established absolute applicability in one continuous domain.
 * A decoded En/P1 or the mathematical 15-minute limit is not evidence for this interval.
 * No satellite clock, tauC, health, signal or datum-transformation usability is implied.
 */
data class GlonassOrbitalEphemeris(
    val orbit: GlonassBroadcastStateVector,
    val referenceTime: GlonassTime,
    val validFrom: GlonassTime,
    val validUntil: GlonassTime,
) {
    init {
        val start = validFrom.secondsSince(referenceTime)
        val end = validUntil.secondsSince(referenceTime)
        require(start != null && end != null && start <= 0 && end >= 0 && end > start)
        require(abs(start) < 900 && abs(end) < 900)
    }
}

/** Absolute-time entry point: refuse mismatched corrections, expired days and stale weeks. */
object GlonassAbsolutePropagator {
    fun propagate(e: GlonassOrbitalEphemeris, transmissionTime: GlonassTime): GlonassPropagationResult {
        val seconds = transmissionTime.secondsSince(e.referenceTime)
            ?: return GlonassPropagationResult(null, "different GLONASS correction/receiver time domain")
        if (transmissionTime.secondsSince(e.validFrom)!! < 0 || e.validUntil.secondsSince(transmissionTime)!! < 0)
            return GlonassPropagationResult(null, "outside absolute GLONASS orbital applicability interval")
        return GlonassBroadcastPropagator.propagate(e.orbit, seconds)
    }
}
