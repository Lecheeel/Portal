package com.system.location.service.core.gnss

/** Mutable-free antenna description; Android's GnssAntennaInfo is built from this model. */
data class AntennaProfile(
    val phaseCenterOffsetMm: List<Double> = listOf(0.0, 0.0, 0.0),
    val phaseCenterVariationMm: List<Double> = listOf(0.0, 0.0, 0.0),
    val signalGainDb: Double = 0.0,
    val gainVariationDb: Double = 0.0,
) {
    init {
        require(phaseCenterOffsetMm.size == 3 && phaseCenterOffsetMm.all { it.isFinite() }) {
            "phaseCenterOffsetMm must be three finite values"
        }
        require(phaseCenterVariationMm.size == 3 && phaseCenterVariationMm.all { it.isFinite() && it >= 0.0 }) {
            "phaseCenterVariationMm must be three non-negative finite values"
        }
        require(signalGainDb.isFinite()) { "signalGainDb must be finite" }
        require(gainVariationDb.isFinite() && gainVariationDb >= 0.0) { "gainVariationDb must be non-negative" }
    }
}

enum class RawAntennaMode { OFF, DEVICE_NEUTRAL, EXPERIMENTAL }

/**
 * P3-05: antenna metadata policy. Raw antenna callbacks stay suppressed in GnssHook; this is the
 * default-off model that a future productionized path can use.
 */
class RawAntennaPolicy(
    val mode: RawAntennaMode = RawAntennaMode.OFF,
    private val experimental: AntennaProfile = AntennaProfile(),
) {
    fun profile(): AntennaProfile? = when (mode) {
        RawAntennaMode.OFF -> null
        RawAntennaMode.DEVICE_NEUTRAL -> AntennaProfile()
        RawAntennaMode.EXPERIMENTAL -> experimental
    }
}
