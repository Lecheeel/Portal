package com.system.location.service.core.network

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * P7-02: advisory time-zone consistency check. It intentionally uses a solar offset from longitude
 * rather than a political time-zone database; callers must treat a mismatch as a warning.
 */
data class TimeZoneConsistency(
    val expectedOffsetMinutes: Int,
    val actualOffsetMinutes: Int,
    val toleranceMinutes: Int,
) {
    init {
        require(expectedOffsetMinutes in TimeZoneConsistencyPolicy.MIN_OFFSET..TimeZoneConsistencyPolicy.MAX_OFFSET) {
            "expected offset out of range"
        }
        require(actualOffsetMinutes in TimeZoneConsistencyPolicy.MIN_OFFSET..TimeZoneConsistencyPolicy.MAX_OFFSET) {
            "actual offset out of range"
        }
        require(toleranceMinutes >= 0) { "tolerance must be non-negative" }
    }

    val deltaMinutes: Int get() = actualOffsetMinutes - expectedOffsetMinutes
    val consistent: Boolean get() = abs(deltaMinutes) <= toleranceMinutes
}

object TimeZoneConsistencyPolicy {
    const val MIN_OFFSET = -12 * 60
    const val MAX_OFFSET = 14 * 60

    fun nominalOffsetMinutes(longitude: Double): Int {
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude out of range" }
        return (longitude / 15.0 * 60.0).roundToInt().coerceIn(MIN_OFFSET, MAX_OFFSET)
    }

    fun check(
        longitude: Double,
        actualOffsetMinutes: Int,
        toleranceMinutes: Int = 60,
    ): TimeZoneConsistency = TimeZoneConsistency(
        expectedOffsetMinutes = nominalOffsetMinutes(longitude),
        actualOffsetMinutes = actualOffsetMinutes,
        toleranceMinutes = toleranceMinutes,
    )

    fun formatOffset(offsetMinutes: Int): String {
        require(offsetMinutes in MIN_OFFSET..MAX_OFFSET) { "offset out of range" }
        val sign = if (offsetMinutes < 0) "-" else "+"
        val absolute = abs(offsetMinutes)
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, absolute / 60, absolute % 60)
    }
}
