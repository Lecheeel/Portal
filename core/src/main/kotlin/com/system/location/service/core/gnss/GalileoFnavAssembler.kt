package com.system.location.service.core.gnss

import kotlin.math.abs

/**
 * Capture-scoped F/NAV accumulation (OS SIS ICD 2.2 §§4.1.2, 5.1.9.2; Tables 30–33).
 * Pages may arrive in any order. IOD identifies only orbit/clock/SISA, not BGD or health.
 * The caller supplies independently established full GST; the decoder applies OS SDD's
 * forward age limit from toe, optionally shortened by independent validity evidence.
 * This class neither derives GST from UTC nor authenticates navigation data. The
 * 120-second window is the assembly policy, NOT an ephemeris validity duration.
 *
 * Results are provisional until completed() rechecks all evidence, including late conflicts.
 * Start a new instance for another capture window; no eviction can rehabilitate a conflict.
 */
class GalileoFnavAssembler(
    private val captureFrom: GalileoTime,
    private val captureUntil: GalileoTime,
    private val validFrom: GalileoTime? = null,
    private val validUntil: GalileoTime? = null,
) {
    init {
        require(captureUntil.secondsSince(captureFrom) in 0.0..120.0)
        require((validFrom == null && validUntil == null) ||
            (validFrom != null && validUntil != null && validUntil.secondsSince(validFrom) > 0))
    }

    private data class Key(val svid: Int, val iod: Int)
    private data class PageEpoch(val type: Int, val time: GalileoTime)
    private data class Part(val page: GalileoNavigationPage, val time: GalileoTime, val reference: GalileoTime)
    private class Candidate {
        val latest = mutableMapOf<Int, Part>()
        val seen = mutableMapOf<PageEpoch, GalileoNavigationPage>()
        var conflict = false
    }
    private data class Service(val key: Key, val part: Part, val conflict: Boolean = false)
    private val candidates = linkedMapOf<Key, Candidate>()
    // Health/BGD are not scoped by IOD. Never fall back to an older healthy page 1 when a
    // newer page 1 belongs to an incomplete, invalid, conflicting, or capacity-rejected issue.
    private val services = mutableMapOf<Int, Service>()
    private var retainedPages = 0

    @Synchronized
    fun completed(): List<DecodedGalileoFnav> = services.toSortedMap().values.mapNotNull { decode(it).decoded }

    @Synchronized
    fun offer(observation: GnssNavigationObservation, trustedTimeAtCapture: GalileoTime): GalileoFnavDecodeResult {
        fun invalid(reason: String) = GalileoFnavDecodeResult(null, reason)
        if (trustedTimeAtCapture.secondsSince(captureFrom) < 0 ||
            captureUntil.secondsSince(trustedTimeAtCapture) < 0)
            return invalid("trusted GST is outside this assembly capture window")
        val checked = GalileoNavigationPages.decode(observation)
        val page = checked.page ?: return invalid(checked.reason ?: "invalid Galileo page")
        if (page.format != GalileoClockReference.FNAV_E1_E5A || page.wordType !in 1..4)
            return invalid("requires an F/NAV ephemeris page 1/2/3/4")
        if (page.wordType == 1 && page.unsigned(6, 6).toInt() != page.svid)
            return invalid("F/NAV SVID disagrees with receiver metadata")
        val time = transmissionTime(page, trustedTimeAtCapture)
            ?: return invalid("F/NAV page time disagrees with trusted GST")
        if (validFrom != null && validUntil != null &&
            (time.secondsSince(validFrom) < 0 || validUntil.secondsSince(time) < 0))
            return invalid("F/NAV transmission outside supplied validity evidence")
        val key = Key(page.svid, page.unsigned(if (page.wordType == 1) 12 else 6, 10).toInt())
        val part = Part(page, time, trustedTimeAtCapture)
        if (page.wordType == 1) {
            val previous = services[page.svid]
            if (previous == null || time.secondsSince(previous.part.time) > 0) services[page.svid] = Service(key, part)
            else if (time == previous.part.time && !sameBits(page, previous.part.page, 0, 214))
                services[page.svid] = previous.copy(conflict = true)
        }
        val candidate = candidates[key] ?: run {
            if (candidates.size >= 64) return invalid("F/NAV candidate capacity exceeded")
            Candidate().also { candidates[key] = it }
        }
        if (candidate.conflict) return invalid("conflicting F/NAV issue")
        val previous = candidate.latest[page.wordType]
        // Only CED/SISA are immutable within an IOD. Exclude GST, BGD, health, ionosphere,
        // UTC/GGTO conversion, and spare fields from this comparison.
        val cedEnd = when (page.wordType) { 1 -> 102; 2 -> 182; 3 -> 174; else -> 48 }
        if (previous != null && !sameBits(page, previous.page, 6, cedEnd)) {
            candidate.conflict = true
            return invalid("conflicting F/NAV orbit/clock/SISA within one issue")
        }
        val epoch = PageEpoch(page.wordType, time)
        val duplicate = candidate.seen[epoch]
        if (duplicate != null && !sameBits(page, duplicate, 0, 214)) {
            candidate.conflict = true
            return invalid("conflicting F/NAV page at the same transmission epoch")
        }
        if (duplicate == null) {
            if (retainedPages >= 512) {
                candidate.conflict = true
                return invalid("F/NAV retained page capacity exceeded")
            }
            candidate.seen[epoch] = page
            retainedPages++
        }
        if (previous == null || time.secondsSince(previous.time) > 0) candidate.latest[page.wordType] = part
        val service = services[page.svid] ?: return invalid("waiting for F/NAV page 1")
        if (service.key != key) return invalid("F/NAV issue superseded by newer service evidence")
        return decode(service)
    }

    private fun decode(service: Service): GalileoFnavDecodeResult {
        fun invalid(reason: String) = GalileoFnavDecodeResult(null, reason)
        if (service.conflict) return invalid("conflicting F/NAV service evidence")
        val candidate = candidates[service.key] ?: return invalid("F/NAV service issue was not retained")
        if (candidate.conflict) return invalid("conflicting F/NAV issue")
        if (candidate.latest.size != 4) return invalid("waiting for F/NAV pages 1/2/3/4")
        val parts = candidate.latest.values
        val earliest = parts.minWith(compareBy<Part> { it.time.week }.thenBy { it.time.secondsOfWeek })
        if (parts.any { it.time.secondsSince(earliest.time) > 120 })
            return invalid("F/NAV transmission epochs are not contemporaneous")
        return GalileoFnavEphemerisDecoder.decode(parts.map { it.page }, service.part.reference, validFrom, validUntil)
    }

    private fun transmissionTime(page: GalileoNavigationPage, ref: GalileoTime): GalileoTime? {
        val weekStart = when (page.wordType) { 1 -> 155; 2 -> 182; 3 -> 174; else -> null }
        val towStart = when (page.wordType) { 1 -> 167; 2 -> 194; 3 -> 186; else -> 189 }
        val tow = page.unsigned(towStart, 20).toDouble()
        if (tow >= 604800) return null
        val weeks = if (weekStart == null) (ref.week.toLong() - 1..ref.week.toLong() + 1).toList()
            else (ref.week / 4096 - 1..ref.week / 4096 + 1).map {
                it.toLong() * 4096 + page.unsigned(weekStart, 12)
            }
        return weeks.filter { it in 0..Int.MAX_VALUE.toLong() }.map { GalileoTime(it.toInt(), tow) }
            .minByOrNull { abs(it.secondsSince(ref)) }?.takeIf { abs(it.secondsSince(ref)) <= 120 }
    }

    private fun sameBits(a: GalileoNavigationPage, b: GalileoNavigationPage, start: Int, end: Int): Boolean =
        (start until end).all { a.unsigned(it, 1) == b.unsigned(it, 1) }
}
