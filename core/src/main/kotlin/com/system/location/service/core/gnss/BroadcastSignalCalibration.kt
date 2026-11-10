package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class BroadcastEmpiricalSignal(val cn0: Double, val used: Double, val almanac: Boolean, val carrier: Float?) {
    init {
        require(cn0.isFinite() && cn0 > 0.0 && cn0 <= 63.0)
        require(used.isFinite() && used in 0.0..1.0)
        require(carrier == null || carrier.isFinite() && carrier > 0f)
    }
}

@Serializable
data class BroadcastSignalKnot(val elevation: Double, val signal: BroadcastEmpiricalSignal) {
    init { require(elevation.isFinite() && elevation in 0.0..90.0) }
}

/** Galileo/BeiDou calibration is per service band; GLONASS FDMA carrier evidence is per slot. */
@Serializable
data class BroadcastSignalCurve(val constellation: Constellation, val nominalCarrierHz: Double?, val svid: Int?,
    val knots: List<BroadcastSignalKnot>) {
    init {
        require(when (constellation) {
            Constellation.GALILEO -> nominalCarrierHz in GALILEO_BANDS && svid == null
            Constellation.BEIDOU -> nominalCarrierHz == BEIDOU_B1I && svid == null
            Constellation.GLONASS -> nominalCarrierHz == null && svid != null && svid in 1..31
            else -> false
        })
        require(knots.isNotEmpty() && knots.size <= GnssCapture.MAX_FRAMES * 512)
        require(knots.zipWithNext().all { (a, b) -> a.elevation < b.elevation })
        require(knots.all { knot -> knot.signal.carrier?.let { carrier ->
            if (nominalCarrierHz != null) abs(carrier.toDouble() - nominalCarrierHz) <= CARRIER_BAND_TOLERANCE_HZ
            else isGlonassL1(carrier)
        } ?: true })
    }

    internal fun at(elevation: Double): BroadcastEmpiricalSignal? {
        if (elevation < knots.first().elevation || elevation > knots.last().elevation) return null
        val index = knots.indexOfFirst { it.elevation >= elevation }
        val right = knots[index]
        if (elevation == right.elevation) return right.signal
        val left = knots[index - 1]
        val fraction = (elevation - left.elevation) / (right.elevation - left.elevation)
        fun mix(a: Double, b: Double) = a + (b - a) * fraction
        return BroadcastEmpiricalSignal(mix(left.signal.cn0, right.signal.cn0), mix(left.signal.used, right.signal.used),
            if (fraction < 0.5) left.signal.almanac else right.signal.almanac,
            left.signal.carrier.takeIf { it == right.signal.carrier })
    }
}

/** Sufficient empirical statistics retained when raw multi-band evidence stays on the device. */
@Serializable
data class BroadcastSignalCalibration(val curves: List<BroadcastSignalCurve>) {
    init {
        require(curves.size <= 35) // Three Galileo bands, B1I and at most 31 GLONASS slots.
        require(curves.map { Triple(it.constellation, it.nominalCarrierHz, it.svid) }.distinct().size == curves.size)
        require(curves.sumOf { it.knots.size.toLong() } <= GnssCapture.MAX_FRAMES.toLong() * 512)
    }

    fun at(constellation: Constellation, elevation: Double, nominalCarrierHz: Double? = null,
        svid: Int? = null): BroadcastEmpiricalSignal? {
        require(elevation.isFinite() && elevation in 0.0..90.0)
        return curves.firstOrNull { it.constellation == constellation && it.nominalCarrierHz == nominalCarrierHz && it.svid == svid }
            ?.at(elevation)
    }

    companion object {
        fun fit(capture: GnssCapture): BroadcastSignalCalibration {
            val samples = capture.frames.flatMap { frame ->
                if (frame.signalObservations.isNotEmpty()) frame.signalObservations.map { it.satellite } else frame.satellites
            }.filter(GnssCaptureValidator::isUsableSatellite)
            val keyed = samples.mapNotNull { sample ->
                val key = when (sample.constellation) {
                    Constellation.GALILEO -> sample.carrierHz?.let { carrier ->
                        GALILEO_BANDS.firstOrNull { abs(carrier.toDouble() - it) <= CARRIER_BAND_TOLERANCE_HZ }
                    }?.let { Triple(sample.constellation, it, null as Int?) }
                    Constellation.BEIDOU -> sample.carrierHz?.takeIf {
                        abs(it.toDouble() - BEIDOU_B1I) <= CARRIER_BAND_TOLERANCE_HZ
                    }?.let { Triple(sample.constellation, BEIDOU_B1I, null as Int?) }
                    Constellation.GLONASS -> if (sample.svid in 1..31 &&
                        (sample.carrierHz == null || isGlonassL1(sample.carrierHz)))
                        Triple(sample.constellation, null as Double?, sample.svid) else null
                    else -> null
                }
                key?.let { it to sample }
            }
            return BroadcastSignalCalibration(keyed.groupBy({ it.first }, { it.second }).map { (key, records) ->
                BroadcastSignalCurve(key.first, key.second, key.third,
                    records.groupBy { it.elevationDeg }.toSortedMap().map { (elevation, bandSamples) ->
                        BroadcastSignalKnot(elevation.toDouble(), BroadcastEmpiricalSignal(
                            bandSamples.map { it.cn0DbHz.toDouble() }.average(),
                            bandSamples.count { it.usedInFix }.toDouble() / bandSamples.size,
                            bandSamples.count { it.hasAlmanac } * 2 >= bandSamples.size,
                            bandSamples.map { it.carrierHz }.distinct().singleOrNull()))
                    })
            }.sortedWith(compareBy({ it.constellation.ordinal }, { it.nominalCarrierHz }, { it.svid })))
        }
    }
}

private val GALILEO_BANDS = listOf(1_575_420_000.0, 1_176_450_000.0, 1_207_140_000.0)
private const val BEIDOU_B1I = 1_561_098_000.0
// RF band identification tolerance only; not a measurement uncertainty or accuracy claim.
private const val CARRIER_BAND_TOLERANCE_HZ = 100_000.0
private fun isGlonassL1(carrier: Float) = (-7..6).any {
    abs(carrier.toDouble() - (1_602_000_000.0 + it * 562_500.0)) <= CARRIER_BAND_TOLERANCE_HZ
}
