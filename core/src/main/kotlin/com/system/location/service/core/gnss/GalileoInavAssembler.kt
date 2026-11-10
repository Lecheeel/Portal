package com.system.location.service.core.gnss

import kotlin.math.abs

/**
 * Capture-scoped I/NAV assembly, OS SIS ICD 2.2 §§4.1.2/5.1.9.2 and Tables 42–46.
 * Words 1–4 carry IODnav but no GST; word 5 carries GST/BGD/health but no IODnav.
 * Consequently an independent trusted GST reference is required for EVERY input, and word 5
 * is maintained per satellite, never copied into an issue as permanently valid service state.
 * The 120s assembly policy is not a fit interval. The decoder applies OS SDD's forward age
 * limit from the decoded toe; optional independent validity limits can only shorten it.
 * No UTC/GPS alias, fixed page order, or OSNMA authenticity is assumed.
 */
class GalileoInavAssembler(
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
    private data class Part(val page: GalileoNavigationPage, val reference: GalileoTime)
    private class Candidate {
        val parts = mutableMapOf<Int, Part>()
        var conflict = false
    }
    private data class Issue(val key: Key, val reference: GalileoTime, val conflict: Boolean = false)
    private data class Service(val part: Part, val transmission: GalileoTime, val conflict: Boolean = false)
    private val candidates = linkedMapOf<Key, Candidate>()
    private val issues = mutableMapOf<Int, Issue>()
    private val services = mutableMapOf<Int, Service>()

    /** Re-evaluate after late conflicts. At most one currently selected issue per satellite. */
    @Synchronized
    fun completed(): List<DecodedGalileoInav> = issues.keys.sorted().mapNotNull { decode(it).decoded }

    @Synchronized
    fun offer(observation: GnssNavigationObservation, trustedTimeAtCapture: GalileoTime): GalileoInavDecodeResult {
        fun invalid(reason: String) = GalileoInavDecodeResult(null, reason)
        val ref = trustedTimeAtCapture
        if (ref.secondsSince(captureFrom) < 0 || captureUntil.secondsSince(ref) < 0)
            return invalid("trusted GST is outside this assembly capture window")
        val checked = GalileoNavigationPages.decode(observation)
        val page = checked.page ?: return invalid(checked.reason ?: "invalid Galileo page")
        if (page.format != GalileoClockReference.INAV_E1_E5B || page.wordType !in 1..5)
            return invalid("requires an I/NAV word 1/2/3/4/5")
        val part = Part(page, ref)
        if (page.wordType == 5) {
            val time = transmissionTime(page, ref) ?: return invalid("I/NAV word 5 time disagrees with trusted GST")
            if (validFrom != null && validUntil != null &&
                (time.secondsSince(validFrom) < 0 || validUntil.secondsSince(time) < 0))
                return invalid("I/NAV transmission outside supplied validity evidence")
            val previous = services[page.svid]
            if (previous == null || time.secondsSince(previous.transmission) > 0) services[page.svid] = Service(part, time)
            else if (time == previous.transmission && !sameBits(page, previous.part.page, 128))
                services[page.svid] = previous.copy(conflict = true)
            return decode(page.svid)
        }
        if (page.wordType == 4 && page.unsigned(16, 6).toInt() != page.svid)
            return invalid("I/NAV SVID disagrees with receiver metadata")
        val key = Key(page.svid, page.unsigned(6, 10).toInt())
        val selected = issues[page.svid]
        // IOD is not an increasing integer across rollover. Use trusted capture order, and
        // reject simultaneous evidence for different issues instead of choosing by arrival.
        if (selected == null || ref.secondsSince(selected.reference) > 0) issues[page.svid] = Issue(key, ref)
        else if (ref == selected.reference && key != selected.key) issues[page.svid] = selected.copy(conflict = true)
        val candidate = candidates[key] ?: run {
            if (candidates.size >= 64) return invalid("I/NAV candidate capacity exceeded")
            Candidate().also { candidates[key] = it }
        }
        if (candidate.conflict) return invalid("conflicting I/NAV issue")
        val previous = candidate.parts[page.wordType]
        val cedEnd = if (page.wordType == 3) 128 else 126
        if (previous != null && !sameBits(page, previous.page, cedEnd)) {
            candidate.conflict = true
            return invalid("conflicting I/NAV orbit/clock/SISA within one issue")
        }
        if (previous == null || ref.secondsSince(previous.reference) > 0) candidate.parts[page.wordType] = part
        return decode(page.svid)
    }

    private fun decode(svid: Int): GalileoInavDecodeResult {
        fun invalid(reason: String) = GalileoInavDecodeResult(null, reason)
        val issue = issues[svid] ?: return invalid("waiting for I/NAV orbit/clock issue")
        if (issue.conflict) return invalid("ambiguous simultaneous I/NAV issues")
        val candidate = candidates[issue.key] ?: return invalid("I/NAV selected issue was not retained")
        if (candidate.conflict) return invalid("conflicting I/NAV issue")
        if (candidate.parts.size != 4) return invalid("waiting for I/NAV words 1/2/3/4")
        val service = services[svid] ?: return invalid("waiting for I/NAV GST/BGD/health word 5")
        if (service.conflict) return invalid("conflicting I/NAV service evidence")
        if (candidate.parts.values.any { abs(it.reference.secondsSince(service.transmission)) > 120 })
            return invalid("I/NAV service time is not contemporaneous with orbit/clock capture")
        return GalileoInavEphemerisDecoder.decode(candidate.parts.values.map { it.page } + service.part.page,
            service.part.reference, validFrom, validUntil)
    }

    private fun transmissionTime(page: GalileoNavigationPage, ref: GalileoTime): GalileoTime? {
        val tow = page.unsigned(85, 20).toDouble()
        if (tow >= 604800) return null
        val week12 = page.unsigned(73, 12)
        return (ref.week / 4096 - 1..ref.week / 4096 + 1).map { it.toLong() * 4096 + week12 }
            .filter { it in 0..Int.MAX_VALUE.toLong() }.map { GalileoTime(it.toInt(), tow) }
            .minByOrNull { abs(it.secondsSince(ref)) }?.takeIf { abs(it.secondsSince(ref)) <= 120 }
    }

    private fun sameBits(a: GalileoNavigationPage, b: GalileoNavigationPage, end: Int): Boolean =
        (0 until end).all { a.unsigned(it, 1) == b.unsigned(it, 1) }
}
