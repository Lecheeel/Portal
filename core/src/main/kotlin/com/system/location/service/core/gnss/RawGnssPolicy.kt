package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

enum class RawGnssMode { OFF, EXPERIMENTAL }

@Serializable
data class RawGnssMeasurement(
    val constellation: Constellation,
    val svid: Int,
    val cn0DbHz: Float,
    val pseudorangeRateMps: Double,
    val carrierPhase: Double?,
    val state: Int,
    val multipath: Int,
    val timeNanos: Long,
) {
    init {
        require(svid in 1..999) { "svid out of range: $svid" }
        require(cn0DbHz in 0f..60f) { "CN0 out of range: $cn0DbHz" }
        require(pseudorangeRateMps.isFinite()) { "pseudorangeRate must be finite" }
        require(carrierPhase == null || carrierPhase.isFinite()) { "carrierPhase must be finite" }
        require(state >= 0) { "state must be non-negative" }
        require(multipath >= 0) { "multipath must be non-negative" }
        require(timeNanos >= 0L) { "timeNanos must be non-negative" }
    }
}

/**
 * Raw measurement policy for P3-05.
 *
 * OFF is the production default and always returns an empty list. EXPERIMENTAL derives deterministic
 * measurements from the same sky model as GnssStatus/NMEA; it is not yet wired into GnssHook.
 */
data class RawGnssPolicy(
    val mode: RawGnssMode = RawGnssMode.OFF,
    val minCn0DbHz: Float = 15f,
    val maxMeasurements: Int = MAX_MEASUREMENTS,
) {
    init {
        require(minCn0DbHz in 0f..60f) { "minCn0DbHz out of range: $minCn0DbHz" }
        require(maxMeasurements in 1..MAX_MEASUREMENTS) { "maxMeasurements out of range: $maxMeasurements" }
    }

    fun measurements(frame: GnssFrame, timeNanos: Long): List<RawGnssMeasurement> {
        require(timeNanos >= 0L) { "timeNanos must be non-negative" }
        if (mode == RawGnssMode.OFF) return emptyList()
        return frame.usedInFix
            .asSequence()
            .filter { it.cn0DbHz >= minCn0DbHz }
            .take(maxMeasurements)
            .map { satellite ->
                RawGnssMeasurement(
                    constellation = satellite.constellation,
                    svid = satellite.svid,
                    cn0DbHz = satellite.cn0DbHz,
                    pseudorangeRateMps = ((satellite.svid * 13 % 100) - 50) / 10.0,
                    carrierPhase = (satellite.svid * 17 % 1_000) / 10.0,
                    state = 1,
                    multipath = 0,
                    timeNanos = timeNanos,
                )
            }
            .toList()
    }

    companion object {
        const val MAX_MEASUREMENTS = 64
    }
}
