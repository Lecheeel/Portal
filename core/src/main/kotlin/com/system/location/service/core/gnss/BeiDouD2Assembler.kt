package com.system.location.service.core.gnss

import kotlin.math.abs

/** Bounded D2 GEO assembly: nine required pages from one 30-second broadcast cycle. */
class BeiDouD2Assembler(
    private val captureFrom: BeiDouTime,
    private val captureUntil: BeiDouTime,
    private val validFrom: BeiDouTime,
    private val validUntil: BeiDouTime,
) {
    private data class Key(val svid: Int, val week: Int, val cycleStart: Double)
    private data class Part(val page: BeiDouNavigationSubframe, val reference: BeiDouTime)
    private data class Candidate(val parts: MutableMap<Int, Part> = linkedMapOf(), var conflict: Boolean = false)
    private val candidates = linkedMapOf<Key, Candidate>()
    private val latest = mutableMapOf<Int, Key>()

    init {
        require(captureUntil.secondsSince(captureFrom) in 0.0..120.0)
        require(validUntil.secondsSince(validFrom) > 0)
    }

    @Synchronized
    fun clear() { candidates.clear(); latest.clear() }

    @Synchronized
    fun completed(): List<BeiDouD2Decoded> = latest.toSortedMap().values.mapNotNull { candidates[it] }
        .filter { !it.conflict && it.parts.keys.containsAll(BeiDouD2EphemerisDecoder.requiredPages) }
        .mapNotNull { decode(it).decoded }

    private fun decode(candidate: Candidate) = BeiDouD2EphemerisDecoder.decode(
        candidate.parts.values.map { it.page }, validFrom, validUntil, candidate.parts.getValue(1).reference)

    @Synchronized
    fun offer(observation: GnssNavigationObservation, trustedBdtAtCapture: BeiDouTime): BeiDouD2DecodeResult {
        fun invalid(reason: String) = BeiDouD2DecodeResult(null, reason)
        if (trustedBdtAtCapture.secondsSince(captureFrom) < 0 || captureUntil.secondsSince(trustedBdtAtCapture) < 0)
            return invalid("BDT reference outside D2 capture window")
        val checked = BeiDouNavigationWords.decode(observation)
        val page = checked.subframe ?: return invalid(checked.reason ?: "invalid BeiDou words")
        if (page.format != BeiDouNavigationFormat.D2 || page.id != 1)
            return invalid("requires BeiDou D2 subframe 1")
        val number = page.rawUnsigned(42, 4).toInt()
        if (number !in BeiDouD2EphemerisDecoder.requiredPages) return invalid("D2 page is not an ephemeris component")
        val sow = page.secondsOfWeek.toDouble()
        val ref = trustedBdtAtCapture
        val full = (ref.week.toLong() - 1..ref.week.toLong() + 1).filter { it in 0..Int.MAX_VALUE.toLong() }
            .map { BeiDouTime(it.toInt(), sow) }.minByOrNull { abs(it.secondsSince(ref)) }
            ?: return invalid("cannot resolve D2 page week")
        if (abs(full.secondsSince(ref)) > 120) return invalid("D2 SOW disagrees with trusted BDT capture reference")
        val cycle = try { full.plusSeconds(-(number - 1) * 3.0) }
            catch (_: IllegalArgumentException) { return invalid("D2 cycle lies before BDT epoch") }
        val key = Key(page.svid, cycle.week, cycle.secondsOfWeek)
        val selection = latest[page.svid]
        if (selection == null || key.week > selection.week ||
            (key.week == selection.week && key.cycleStart > selection.cycleStart)) latest[page.svid] = key
        val candidate = candidates[key] ?: run {
            if (candidates.size >= 64) return invalid("BeiDou D2 candidate capacity exceeded")
            Candidate().also { candidates[key] = it }
        }
        if (candidate.conflict) return invalid("conflicting BeiDou D2 candidate")
        val previous = candidate.parts[number]
        if (previous != null) {
            if ((0 until 28).any { previous.page.unsigned(it * 8, 8) != page.unsigned(it * 8, 8) }) {
                candidate.conflict = true
                candidate.parts.clear()
                return invalid("conflicting duplicate BeiDou D2 page")
            }
        } else candidate.parts[number] = Part(page, ref)
        if (latest[page.svid] != key) return invalid("BeiDou D2 broadcast cycle superseded")
        if (!candidate.parts.keys.containsAll(BeiDouD2EphemerisDecoder.requiredPages))
            return invalid("waiting for BeiDou D2 pages 1 and 3..10")
        return decode(candidate)
    }
}
