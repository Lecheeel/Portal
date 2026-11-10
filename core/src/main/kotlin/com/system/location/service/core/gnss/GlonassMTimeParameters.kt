package com.system.location.service.core.gnss

/**
 * GLONASS-M string 5 fields, not an accepted clock conversion or applicability interval.
 * tauC relates GLONASS time to UTC(SU); tauGps is ONLY the fractional GPS-minus-GLONASS
 * offset. The independent integer offset, UTC(SU)-UTC and leap/receiver domains are absent.
 * A zero coefficient is retained as a value, never evidence that an offset is available.
 */
data class GlonassMTimeParameters(
    val sourceSlot: Int,
    val frameId: Int,
    val capturedAtMillis: Long,
    val fourYearIntervalNumber: Int,
    val almanacDayNumber: Int,
    /** Beginning of NA, a calendar label, not a continuous physical transmission epoch. */
    val almanacDayStart: GlonassCalendarCoordinate,
    val tauCSeconds: Double,
    val tauGpsFractionalSeconds: Double,
    val healthLn: Int,
)

data class GlonassMTimeParametersResult(val parameters: GlonassMTimeParameters?, val reason: String? = null)

/** ICD 5.1 §4.5/Tables 4.9/4.11, GLONASS-M (M=01) only. No satellite variant guessing. */
object GlonassMTimeParametersDecoder {
    /**
     * satelliteTypeBits must come from independently associated, accepted format evidence
     * (the M field in string 4), not a historical slot list or an assumed current satellite type.
     * The string must already have passed GlonassNavigationStrings parity/metadata checks.
     */
    fun decode(string: GlonassNavigationString, satelliteTypeBits: Int): GlonassMTimeParametersResult {
        fun invalid(reason: String) = GlonassMTimeParametersResult(null, reason)
        if (satelliteTypeBits != 1) return invalid("requires independently established GLONASS-M message format")
        if (string.stringId != 5) return invalid("requires GLONASS-M time-parameter string 5")
        val n4 = string.unsigned(36, 5).toInt()
        val na = string.unsigned(80, 11).toInt()
        val day = GlonassCalendarCoordinate.fromBroadcastDate(n4, na, 0.0)
            ?: return invalid("invalid GLONASS-M N4/NA calendar date")
        return GlonassMTimeParametersResult(GlonassMTimeParameters(
            string.slot, string.frameId, string.capturedAtMillis, n4, na, day,
            string.signMagnitude(69, 32) * Math.scalb(1.0, -31),
            // §4.5 explicitly defines the fractional offset in seconds. Table 4.9's
            // printed 'day' unit does not change that definition or the 2^-30 scaling.
            string.signMagnitude(31, 22) * Math.scalb(1.0, -30),
            string.unsigned(9, 1).toInt(),
        ))
    }
}
