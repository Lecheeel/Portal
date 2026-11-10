package com.system.location.service.core.gnss

import kotlin.math.abs

/** B1I truncated week and weekless clock/orbit epoch association, not a validity policy. */
internal object BeiDouDecodeEpochs {
    fun transmission(week13: Int, sow: Double, reference: BeiDouTime): BeiDouTime? {
        if (week13 !in 0..8191 || sow !in 0.0..<604800.0) return null
        val era = reference.week / 8192
        val week = (era - 1..era + 1).map { it.toLong() * 8192 + week13 }
            .filter { it in 0..Int.MAX_VALUE.toLong() }.minByOrNull { abs(it - reference.week) }?.toInt()
            ?: return null
        return BeiDouTime(week, sow).takeIf { abs(it.secondsSince(reference)) <= 120 }
    }

    fun epoch(seconds: Double, transmission: BeiDouTime): BeiDouTime? {
        if (seconds !in 0.0..<604800.0) return null
        val week = transmission.week.toLong()
        val candidates = (week - 1..week + 1).filter { it in 0..Int.MAX_VALUE.toLong() }
            .map { BeiDouTime(it.toInt(), seconds) }
        val closest = candidates.minByOrNull { abs(it.secondsSince(transmission)) } ?: return null
        val distance = abs(closest.secondsSince(transmission))
        if (distance >= 302400) return null // exactly half a week is ambiguous, including at the epoch
        return closest
    }
}
