package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable

enum class BeiDouLegacySignal { B1I, B2I }

/** Independently established service applicability; an orbit fit interval is not health evidence. */
@Serializable
data class BeiDouServiceValidity(val from: BeiDouTime, val until: BeiDouTime) {
    init { require(until.secondsSince(from) > 0) }
    fun contains(time: BeiDouTime) = time.secondsSince(from) >= 0 && until.secondsSince(time) >= 0
}

data class BeiDouSignalClockResult(val correctionSeconds: Double?, val reason: String? = null)

/** B1I 3.0 §§5.2.4.5/6/9/10: B1I clock minus TGD1; B2I clock minus TGD2. */
object BeiDouSignalClock {
    fun correction(decoded: BeiDouD1Decoded, transmission: BeiDouTime, signal: BeiDouLegacySignal,
        serviceValidity: BeiDouServiceValidity): BeiDouSignalClockResult {
        if (decoded.ephemeris.orbitType == BeiDouOrbitType.GEO)
            return BeiDouSignalClockResult(null, "D1 service evidence cannot describe GEO")
        return evaluate(decoded.ephemeris, decoded.health, decoded.uraIndex, decoded.tgd1Seconds, decoded.tgd2Seconds,
            decoded.transmissionTime, transmission, signal, serviceValidity)
    }

    fun correction(decoded: BeiDouD2Decoded, transmission: BeiDouTime, signal: BeiDouLegacySignal,
        serviceValidity: BeiDouServiceValidity): BeiDouSignalClockResult {
        if (decoded.ephemeris.orbitType != BeiDouOrbitType.GEO)
            return BeiDouSignalClockResult(null, "D2 service evidence requires GEO")
        return evaluate(decoded.ephemeris, decoded.health, decoded.uraIndex, decoded.tgd1Seconds, decoded.tgd2Seconds,
            decoded.transmissionTimeOfPageOne, transmission, signal, serviceValidity)
    }

    private fun evaluate(e: BeiDouBroadcastEphemeris, health: Int, ura: Int, tgd1: Double, tgd2: Double,
        serviceBroadcast: BeiDouTime, transmission: BeiDouTime, signal: BeiDouLegacySignal,
        serviceValidity: BeiDouServiceValidity): BeiDouSignalClockResult {
        fun invalid(reason: String) = BeiDouSignalClockResult(null, reason)
        if (!serviceValidity.contains(serviceBroadcast) || !serviceValidity.contains(transmission))
            return invalid("BeiDou service evidence outside independently supplied applicability interval")
        if (health != 0) return invalid("BeiDou autonomous health flag is not good")
        if (ura !in 0..14) return invalid("BeiDou URAI indicates maneuver, no accuracy prediction or invalid data")
        val tgd = if (signal == BeiDouLegacySignal.B1I) tgd1 else tgd2
        if (!tgd.isFinite() || tgd < -512 * 0.1e-9 || tgd > 511 * 0.1e-9)
            return invalid("BeiDou signal TGD outside broadcast field range")
        val propagated = BeiDouBroadcastPropagator.propagate(e, transmission)
        val state = propagated.state ?: return invalid(propagated.reason ?: "unavailable BeiDou orbit/clock")
        val bias = state.clockBiasSeconds - tgd
        if (!bias.isFinite()) return invalid("non-finite BeiDou single-frequency clock")
        return BeiDouSignalClockResult(bias)
    }
}
