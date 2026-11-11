package com.system.location.service.core.gnss

data class RinexGlonassBroadcast(
    val orbit: GlonassBroadcastStateVector,
    val referenceUtc: RinexNavigationEpoch,
    /** RINEX already stores -tauN; do not negate it a second time. */
    val clockBiasSeconds: Double,
    val relativeFrequencyBias: Double,
    val frameTimeUtcWeekSeconds: Double,
    val unhealthy: Boolean,
    val frequencyNumber: Int,
    val ageDays: Int,
    val statusFlags: Int?,
    val l1L2GroupDelaySeconds: Double?,
    val accuracyIndex: Int?,
    val healthFlags: Int?,
)

/**
 * RINEX 3.05 A10: km, km/s and km/s² become SI exactly once; UTC labels are NOT UTC+3.
 * The caller supplies the PZ-90 realization. Health/status and message age do not establish
 * a continuous time domain, an applicability interval or a WGS84 datum transformation.
 */
object RinexGlonassImporter {
    fun decode(record: RinexNavigationRecord, referenceFrame: GlonassReferenceFrame): RinexGlonassBroadcast {
        require(record.system == RinexNavigationSystem.GLONASS && record.orbit.size in 3..4)
        val f = RinexOrbitFields(record)
        fun vector(column: Int) = GlonassCartesianVector(f.orbit(0, column) * 1000,
            f.orbit(1, column) * 1000, f.orbit(2, column) * 1000)
        val state = GlonassBroadcastStateVector(record.svid, referenceFrame, vector(0), vector(1), vector(2))
        val frameTime = f.clock(2)
        require(frameTime >= 0 && frameTime < 604801) { "Invalid GLONASS UTC frame time" }
        val extra = record.orbit.size == 4
        fun optionalInteger(column: Int, range: IntRange): Int? =
            if (extra && record.orbit[3][column] != null) f.integer(3, column, range) else null
        val status = optionalInteger(0, 0..511)
        val delay = if (extra) record.orbit[3][1]?.also { require(it.isFinite()) }
            ?.takeUnless { it == 999999999.999 } else null
        val accuracy = optionalInteger(2, 0..15)?.takeUnless { it == 15 }
        return RinexGlonassBroadcast(state, record.epoch, f.clock(0), f.clock(1), frameTime,
            f.integer(0, 3, 0..1) != 0,
            // RINEX permits -7..+13; +7..+13 do not establish a usable FDMA carrier.
            f.integer(1, 3, -7..13), f.integer(2, 3, 0..31), status, delay, accuracy,
            optionalInteger(3, 0..7))
    }
}
