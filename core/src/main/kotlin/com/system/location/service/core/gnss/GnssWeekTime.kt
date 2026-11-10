package com.system.location.service.core.gnss

import kotlin.math.floor

/** Canonical Double week/TOW representation, shared by system-time arithmetic and GGTO. */
internal object GnssWeekTime {
    fun normalize(week: Long, seconds: Double): Pair<Int, Double>? {
        if (!seconds.isFinite()) return null
        var change = floor(seconds / 604800.0)
        // Division of the smallest negative subnormal values can underflow to -0.0.
        if (seconds < 0.0 && change == 0.0) change = -1.0
        // Check before converting arbitrarily large Double shifts to Long or adding weeks.
        if (change < -week.toDouble() || change > Int.MAX_VALUE.toDouble() - week) return null
        var normalizedWeek = week + change.toLong()
        var tow = seconds - change * 604800.0
        // A negative sub-ULP offset can round the preceding week's remainder to 604800.
        // Canonicalize that rounded representation, without accepting a negative epoch.
        if (tow == 604800.0) { normalizedWeek++; tow = 0.0 }
        if (normalizedWeek > Int.MAX_VALUE || tow !in 0.0..<604800.0) return null
        return normalizedWeek.toInt() to tow
    }
}
