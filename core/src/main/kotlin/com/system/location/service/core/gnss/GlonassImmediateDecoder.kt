package com.system.location.service.core.gnss

/**
 * Decoded GLONASS-M FDMA immediate fields. This is NOT an accepted absolute ephemeris.
 * tk/tb are system-local labels without a resolved four-year interval/leap association.
 * Frame-ID and arrival-window consistency do not prove that the four strings are coeval.
 * Health bits, age and update flags are evidence, not an inferred service validity interval.
 */
data class GlonassImmediateFields(
    val orbit: GlonassBroadcastStateVector,
    val frameTimeSecondsOfDay: Int,
    val referenceTimeSecondsOfDay: Int,
    val dayInFourYearInterval: Int,
    val tauNSeconds: Double,
    val gammaN: Double,
    val deltaTauNSeconds: Double,
    val healthBn: Int,
    val healthLn: Int,
    val accuracyFt: Int,
    val ageDays: Int,
    val updateP1: Int,
    val oddnessP2: Int,
    val almanacCountP3: Int,
    val ephemerisUpdateP4: Int,
    val timeParameterModeP: Int,
)

data class GlonassImmediateDecodeResult(val fields: GlonassImmediateFields?, val reason: String? = null)

/** ICD 5.1 Tables 4.5/4.6; GLONASS-M (M=01) only. No implicit epoch or frame acceptance. */
object GlonassImmediateDecoder {
    fun decode(strings: List<GlonassNavigationString>, referenceFrame: GlonassReferenceFrame): GlonassImmediateDecodeResult {
        fun invalid(reason: String) = GlonassImmediateDecodeResult(null, reason)
        if (strings.size != 4 || strings.map { it.stringId }.toSet() != setOf(1, 2, 3, 4))
            return invalid("requires exactly GLONASS immediate strings 1..4")
        if (strings.map { it.slot }.distinct().size != 1 || strings.map { it.frameId }.distinct().size != 1)
            return invalid("GLONASS slot/frame metadata mismatch")
        // Bounded transport sanity only; a later assembler must independently associate each string.
        if (strings.maxOf { it.capturedAtMillis } - strings.minOf { it.capturedAtMillis } > 30_000)
            return invalid("GLONASS immediate arrival window exceeds one frame")
        val byId = strings.associateBy { it.stringId }
        val s1 = byId.getValue(1); val s2 = byId.getValue(2)
        val s3 = byId.getValue(3); val s4 = byId.getValue(4)
        fun GlonassNavigationString.u(high: Int, length: Int) = unsigned(high, length).toInt()
        if (s4.u(10, 2) != 1) return invalid("unsupported GLONASS satellite message variant")
        if (s4.u(15, 5) != s1.slot) return invalid("GLONASS encoded slot differs from receiver identity")
        val day = s4.u(26, 11)
        val tb = s2.u(76, 7)
        val hours = s1.u(76, 5); val minutes = s1.u(71, 6)
        if (day !in 1..1461 || tb !in 1..95 || hours !in 0..23 || minutes !in 0..59)
            return invalid("invalid GLONASS date or time label")
        val axes = listOf(s1, s2, s3)
        fun vector(high: Int, bits: Int, scale: Double): GlonassCartesianVector {
            val v = axes.map { it.signMagnitude(high, bits) * scale }
            return GlonassCartesianVector(v[0], v[1], v[2])
        }
        val orbit = try {
            GlonassBroadcastStateVector(s1.slot, referenceFrame, vector(35, 27, 1000.0 / 2048),
                vector(64, 24, 1000.0 / 1_048_576), vector(40, 5, 1000.0 / 1_073_741_824))
        } catch (_: IllegalArgumentException) {
            return invalid("invalid GLONASS immediate orbital state")
        }
        return GlonassImmediateDecodeResult(GlonassImmediateFields(orbit,
            hours * 3600 + minutes * 60 + s1.u(65, 1) * 30, tb * 900, day,
            s4.signMagnitude(80, 22) / 1_073_741_824.0,
            s3.signMagnitude(79, 11) / 1_099_511_627_776.0,
            s4.signMagnitude(58, 5) / 1_073_741_824.0,
            s2.u(80, 3), s3.u(65, 1), s4.u(33, 4), s4.u(53, 5),
            s1.u(78, 2), s2.u(77, 1), s3.u(80, 1), s4.u(34, 1), s3.u(67, 2)))
    }
}
