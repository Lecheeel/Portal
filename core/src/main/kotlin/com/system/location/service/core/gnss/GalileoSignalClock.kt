package com.system.location.service.core.gnss

enum class GalileoOpenServiceSignal { E1, E5A, E5B }

data class GalileoSignalClockResult(val correctionSeconds: Double?, val reason: String? = null)

/**
 * OS SIS ICD 2.2 §5.1.5, Eqs 18/19, Tables 2/71/73. Single-frequency clock correction
 * for the services supported by each decoded message, including that signal's SHS/DVS.
 * This is the satellite correction subtracted from a measured TOT, not a pseudorange model.
 * Dual-frequency clock propagation remains in GalileoBroadcastPropagator without a BGD term.
 */
object GalileoSignalClock {
    private const val E1_HZ = 1575420000.0
    private const val E5A_HZ = 1176450000.0
    private const val E5B_HZ = 1207140000.0

    fun correction(decoded: DecodedGalileoInav, transmission: GalileoTime,
        signal: GalileoOpenServiceSignal): GalileoSignalClockResult {
        if (decoded.ephemeris.clockReference != GalileoClockReference.INAV_E1_E5B)
            return invalid("I/NAV parameters have an inconsistent clock reference")
        if (signal == GalileoOpenServiceSignal.E5A)
            return invalid("I/NAV single-frequency service does not supply E5a clock/status evidence")
        if (decoded.sisaIndex !in 0..125) return invalid("I/NAV SISA is spare or NAPA")
        val status = if (signal == GalileoOpenServiceSignal.E1) decoded.e1Status else decoded.e5bStatus
        val factor = if (signal == GalileoOpenServiceSignal.E1) 1.0 else squaredRatio(E1_HZ, E5B_HZ)
        return evaluate(decoded.ephemeris, transmission, status, decoded.bgdE1E5bSeconds, factor)
    }

    fun correction(decoded: DecodedGalileoFnav, transmission: GalileoTime,
        signal: GalileoOpenServiceSignal): GalileoSignalClockResult {
        if (decoded.ephemeris.clockReference != GalileoClockReference.FNAV_E1_E5A)
            return invalid("F/NAV parameters have an inconsistent clock reference")
        if (signal != GalileoOpenServiceSignal.E5A)
            return invalid("F/NAV single-frequency service supplies only E5a clock/status evidence")
        if (decoded.sisaIndex !in 0..125) return invalid("F/NAV SISA is spare or NAPA")
        return evaluate(decoded.ephemeris, transmission, decoded.e5aStatus, decoded.bgdE1E5aSeconds,
            squaredRatio(E1_HZ, E5A_HZ))
    }

    private fun evaluate(e: GalileoBroadcastEphemeris, transmission: GalileoTime,
        status: GalileoSignalStatus, bgd: Double, factor: Double): GalileoSignalClockResult {
        if (!status.usable) return invalid("requested signal health/data validity is unusable")
        // A decoded BGD is a signed ten-bit value scaled by 2^-32 seconds (Table 72).
        if (!bgd.isFinite() || bgd < -512 * Math.scalb(1.0, -32) || bgd > 511 * Math.scalb(1.0, -32))
            return invalid("group delay outside broadcast field range")
        val propagation = GalileoBroadcastPropagator.propagate(e, transmission)
        val state = propagation.state ?: return invalid(propagation.reason ?: "Galileo propagation unavailable")
        val clock = state.clockBiasSeconds - factor * bgd
        return if (clock.isFinite()) GalileoSignalClockResult(clock) else invalid("non-finite signal clock")
    }

    private fun squaredRatio(a: Double, b: Double) = (a / b) * (a / b)
    private fun invalid(reason: String) = GalileoSignalClockResult(null, reason)
}
