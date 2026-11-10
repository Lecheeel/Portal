package com.system.location.service.core.gnss

/** Joined orbital/clock fields and a resolved tb; not proof of signal/service usability. */
data class GlonassAssembledImmediate(
    val fields: GlonassImmediateFields,
    /** tb is labelled UTC(SU)+3h, per ICD §4.4. It is not the satellite's tk clock. */
    val referenceTime: GlonassTime,
    /** Nominal frame schedule on the satellite clock; NOT an exact physical emission epoch. */
    val frameScheduleCoordinate: GlonassCalendarCoordinate,
)

data class GlonassAssemblyResult(val decoded: GlonassAssembledImmediate?, val reason: String? = null)

/**
 * One bounded capture/clock domain. Each string needs independently established complete
 * SATELLITE SCHEDULE labels, not callback arrival times or physical system times substituted
 * for tk. The schedule has 30s frames/2s strings; satellite clock bias remains separate.
 * Unassociated timing, clock changes, newer incomplete frames and conflicts withdraw output.
 * completed() must be re-read after capture ends; previously returned objects are not final.
 */
class GlonassL1caAssembler(
    private val captureFrom: GlonassTime,
    private val captureUntil: GlonassTime,
    private val referenceFrame: GlonassReferenceFrame,
) {
    private data class Key(val slot: Int, val day: Long, val second: Int)
    private data class Candidate(val parts: MutableMap<Int, GlonassNavigationString> = linkedMapOf(), var conflict: Boolean = false)
    private val candidates = linkedMapOf<Key, Candidate>()
    private val latest = mutableMapOf<Int, Key>()
    private val quarantined = mutableSetOf<Int>()

    init { require(captureUntil.secondsSince(captureFrom)?.let { it in 0.0..120.0 } == true) }

    @Synchronized fun clear() { candidates.clear(); latest.clear(); quarantined.clear() }

    @Synchronized fun completed(): List<GlonassAssembledImmediate> = latest.toSortedMap().mapNotNull { (slot, key) ->
        if (slot in quarantined) null else candidates[key]?.takeIf { !it.conflict && it.parts.size == 4 }
            ?.let { assemble(key, it).decoded }
    }

    @Synchronized
    fun offer(o: GnssNavigationObservation, independentlyResolvedSchedule: GlonassTime?): GlonassAssemblyResult {
        fun invalid(reason: String) = GlonassAssemblyResult(null, reason)
        if (o.type != 0x0301 || o.svid !in 1..31 || o.submessageId !in 1..4)
            return invalid("requires GLONASS immediate string 1..4")
        val schedule = independentlyResolvedSchedule
        if (schedule == null || schedule.domain !== captureFrom.domain) {
            quarantined += o.svid
            return invalid("missing GLONASS schedule evidence or changed correction/receiver domain")
        }
        if (schedule.secondsSince(captureFrom)!! < 0 || captureUntil.secondsSince(schedule)!! < 0)
            return invalid("GLONASS schedule outside capture window")
        val frame = schedule.plusSeconds(-2.0 * (o.submessageId - 1))
        if (frame == null || frame.coordinate.secondsOfDay % 30.0 != 0.0) {
            quarantined += o.svid
            return invalid("GLONASS schedule is not a complete nominal string-start label")
        }
        val key = Key(o.svid, frame.coordinate.date.toEpochDay(), frame.coordinate.secondsOfDay.toInt())
        val previous = latest[o.svid]
        if (previous == null || key.day > previous.day || (key.day == previous.day && key.second > previous.second)) latest[o.svid] = key
        val candidate = candidates[key] ?: run {
            // Advance latest before rejecting capacity: replay must not restore older output.
            if (candidates.size >= 64) return invalid("GLONASS assembly capacity exceeded")
            Candidate().also { candidates[key] = it }
        }
        if (o.svid in quarantined || candidate.conflict) return invalid("GLONASS candidate quarantined or conflicting")
        val checked = GlonassNavigationStrings.decode(o)
        val string = checked.string
        if (string == null) {
            candidate.conflict = true; candidate.parts.clear()
            return invalid(checked.reason ?: "invalid GLONASS string")
        }
        val encodedTk = if (string.stringId == 1)
            string.unsigned(76, 5) * 3600 + string.unsigned(71, 6) * 60 + string.unsigned(65, 1) * 30 else null
        if (string.frameId != key.second / 30 % 5 + 1 || (encodedTk != null && encodedTk != key.second.toLong())) {
            candidate.conflict = true; candidate.parts.clear()
            return invalid("GLONASS string metadata/tk disagrees with independent schedule")
        }
        val old = candidate.parts[string.stringId]
        if (old != null && (old.frameId != string.frameId || (9..84).any { old.unsigned(it, 1) != string.unsigned(it, 1) })) {
            candidate.conflict = true; candidate.parts.clear()
            return invalid("conflicting duplicate GLONASS string")
        }
        if (old == null) candidate.parts[string.stringId] = string
        if (latest[o.svid] != key) return invalid("GLONASS transmission frame superseded")
        if (candidate.parts.size != 4) return invalid("waiting for GLONASS strings 1/2/3/4")
        return assemble(key, candidate)
    }

    private fun assemble(key: Key, candidate: Candidate): GlonassAssemblyResult {
        fun invalid(reason: String) = GlonassAssemblyResult(null, reason)
        val result = GlonassImmediateDecoder.decode(candidate.parts.values.toList(), referenceFrame)
        val fields = result.fields ?: return invalid(result.reason ?: "invalid GLONASS immediate fields")
        if (fields.frameTimeSecondsOfDay != key.second || candidate.parts.getValue(1).frameId != key.second / 30 % 5 + 1)
            return invalid("GLONASS tk/frame ID disagrees with independent schedule")
        val date = java.time.LocalDate.ofEpochDay(key.day)
        val nominal = GlonassCalendarCoordinate(date, key.second.toDouble())
        val epoch = GlonassReferenceEpoch.resolve(fields.dayInFourYearInterval, fields.referenceTimeSecondsOfDay, nominal, captureFrom.domain)
        val reference = epoch.time ?: return invalid(epoch.reason ?: "invalid GLONASS reference epoch")
        return GlonassAssemblyResult(GlonassAssembledImmediate(fields, reference, nominal))
    }
}
