package com.system.location.service.core.gnss

import kotlin.math.abs

/** One bounded capture domain; D1 parts are joined only within the same 30-second frame. */
class BeiDouD1Assembler(
    private val captureFrom: BeiDouTime,
    private val captureUntil: BeiDouTime,
    private val validFrom: BeiDouTime,
    private val validUntil: BeiDouTime,
    orbitTypes: Map<Int, BeiDouOrbitType>,
) {
    private val types = orbitTypes.toMap()
    private data class Key(val svid: Int, val week: Int, val frameStart: Int)
    private data class Part(val frame: BeiDouNavigationSubframe, val reference: BeiDouTime)
    private data class Candidate(val parts: MutableMap<Int, Part> = linkedMapOf(), var conflict: Boolean = false)
    private val candidates = linkedMapOf<Key, Candidate>()
    private val latest = mutableMapOf<Int, Key>()

    init {
        require(captureUntil.secondsSince(captureFrom) in 0.0..120.0)
        require(validUntil.secondsSince(validFrom) > 0)
        require(types.all { (svid, type) -> svid in 1..63 && type != BeiDouOrbitType.GEO })
    }

    @Synchronized
    fun clear() { candidates.clear(); latest.clear() }

    /** Recompute after late arrivals: an earlier returned value is not a final capture result. */
    @Synchronized
    fun completed(): List<BeiDouD1Decoded> = latest.toSortedMap().values.mapNotNull { candidates[it] }
        .filter { !it.conflict && it.parts.size == 3 }.mapNotNull { decode(it).decoded }

    private fun decode(candidate: Candidate) = BeiDouD1EphemerisDecoder.decode(
        candidate.parts.values.map { it.frame }, validFrom, validUntil,
        types.getValue(candidate.parts.getValue(1).frame.svid), candidate.parts.getValue(1).reference)

    @Synchronized
    fun offer(observation: GnssNavigationObservation, trustedBdtAtCapture: BeiDouTime): BeiDouD1DecodeResult {
        fun invalid(reason: String) = BeiDouD1DecodeResult(null, reason)
        if (trustedBdtAtCapture.secondsSince(captureFrom) < 0 || captureUntil.secondsSince(trustedBdtAtCapture) < 0)
            return invalid("BDT reference outside D1 capture window")
        val checked = BeiDouNavigationWords.decode(observation)
        val frame = checked.subframe ?: return invalid(checked.reason ?: "invalid BeiDou words")
        if (frame.format != BeiDouNavigationFormat.D1 || frame.id !in 1..3)
            return invalid("requires BeiDou D1 ephemeris component")
        if (frame.svid !in types) return invalid("missing independent MEO/IGSO orbit-type evidence")
        val sow = frame.secondsOfWeek.toDouble()
        val ref = trustedBdtAtCapture
        val week = (ref.week.toLong() - 1..ref.week.toLong() + 1).filter { it in 0..Int.MAX_VALUE.toLong() }
            .minByOrNull { abs(BeiDouTime(it.toInt(), sow).secondsSince(ref)) }?.toInt()
            ?: return invalid("cannot resolve D1 frame week")
        if (abs(BeiDouTime(week, sow).secondsSince(ref)) > 120)
            return invalid("D1 SOW disagrees with trusted BDT capture reference")
        // D1's first three subframes fit entirely inside one week (604800 is divisible by 30).
        val key = Key(frame.svid, week, frame.secondsOfWeek - (frame.id - 1) * 6)
        val previousSelection = latest[frame.svid]
        if (previousSelection == null || key.week > previousSelection.week ||
            (key.week == previousSelection.week && key.frameStart > previousSelection.frameStart)) latest[frame.svid] = key
        val candidate = candidates[key] ?: run {
            // Retain conflicting candidates. Flooding and replay cannot erase their evidence.
            // Selection has already advanced, so a rejected newer frame still withdraws old output.
            if (candidates.size >= 64) return invalid("BeiDou D1 candidate capacity exceeded")
            Candidate().also { candidates[key] = it }
        }
        if (candidate.conflict) return invalid("conflicting BeiDou D1 candidate")
        val previous = candidate.parts[frame.id]
        if (previous != null) {
            if ((0 until 28).any { previous.frame.unsigned(it * 8, 8) != frame.unsigned(it * 8, 8) }) {
                candidate.conflict = true
                candidate.parts.clear()
                return invalid("conflicting duplicate BeiDou D1 component")
            }
        } else candidate.parts[frame.id] = Part(frame, ref)
        if (latest[frame.svid] != key) return invalid("BeiDou D1 transmission frame superseded")
        if (candidate.parts.size != 3) return invalid("waiting for BeiDou D1 subframes 1/2/3")
        return decode(candidate)
    }
}
