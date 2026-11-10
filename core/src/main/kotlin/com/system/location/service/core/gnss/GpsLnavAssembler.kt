package com.system.location.service.core.gnss

import kotlin.math.abs

/**
 * Bounded CEI assembly, one satellite/transmission frame per candidate. Each input requires a
 * caller-supplied trusted full GPS reference; capture UTC is never silently treated as GPS time.
 * The returned ephemeris is evidence, not permission to publish a complete multi-system sky.
 */
class GpsLnavAssembler {
    private data class Key(val svid: Int, val week: Int, val frameStartSeconds: Int)
    private data class Part(val frame: GpsLnavSubframe, val reference: GpsTime)
    private data class Candidate(val parts: MutableMap<Int, Part> = linkedMapOf(), var conflict: Boolean = false)
    private val candidates = object : LinkedHashMap<Key, Candidate>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Candidate>?): Boolean = size > 64
    }

    @Synchronized
    fun clear() = candidates.clear()

    @Synchronized
    fun offer(observation: GnssNavigationObservation, trustedGpsTimeAtCapture: GpsTime): GpsLnavDecodeResult {
        val checked = GpsLnavWords.decode(observation)
        val frame = checked.subframe ?: return GpsLnavDecodeResult(null, checked.reason)
        if (frame.id !in 1..3) return GpsLnavDecodeResult(null, "GPS subframe does not contain a CEI component")
        val start = frame.nextTowSeconds - frame.id * 6
        val ref = trustedGpsTimeAtCapture
        // GPS subframes 1/2/3 fit entirely within one week and one 30-second frame.
        val week = (ref.week.toLong() - 1..ref.week.toLong() + 1)
            .filter { it in 0..Int.MAX_VALUE.toLong() }
            .minByOrNull { abs(GpsTime(it.toInt(), (start + frame.id * 6).toDouble()).secondsSince(ref)) }
            ?.toInt() ?: return GpsLnavDecodeResult(null, "cannot resolve transmission frame week")
        if (abs(GpsTime(week, (start + frame.id * 6).toDouble()).secondsSince(ref)) > 120)
            return GpsLnavDecodeResult(null, "HOW disagrees with trusted capture reference")
        val key = Key(frame.svid, week, start)
        val candidate = candidates.getOrPut(key) { Candidate() }
        if (candidate.conflict) return GpsLnavDecodeResult(null, "conflicting GPS subframe candidate")
        val previous = candidate.parts[frame.id]
        if (previous != null) {
            // A duplicate with different parity-free information poisons this candidate. Do not
            // pick whichever page happened to arrive last during upload/corruption.
            if ((0 until 30).any { previous.frame.unsigned(it * 8, 8) != frame.unsigned(it * 8, 8) }) {
                candidate.conflict = true
                candidate.parts.clear()
                return GpsLnavDecodeResult(null, "conflicting duplicate GPS subframe")
            }
        } else candidate.parts[frame.id] = Part(frame, ref)
        if (candidate.parts.size != 3) return GpsLnavDecodeResult(null, "waiting for GPS subframes 1/2/3")
        return GpsLnavEphemerisDecoder.decode(candidate.parts.values.map { it.frame },
            candidate.parts.getValue(1).reference)
    }
}
