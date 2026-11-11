package com.system.location.service.core.gnss

/**
 * Select an explicitly limited recent-upload snapshot from a bounded daily archive. Keep the
 * original headers/records verbatim; never retime, synthesize or change health fields to fit IPC.
 * GPS/GAL/BDS ordering uses native full-week transmission time (not download time or toe).
 * Missing/invalid transmission records are retained conservatively, not silently ranked as old.
 */
object RinexNavigationSnapshot {
    fun select(archive: String, sourceUrl: String, downloadedAtMillis: Long,
        uploadsPerIdentity: Int = 2): RinexNavigationResource {
        require(uploadsPerIdentity in 1..32)
        val parsed = RinexNavigationReader.read(archive)
        val lines = archive.lineSequence().toList()
        val headerEnd = lines.indexOfFirst { it.length >= 60 && it.substring(60).trim() == "END OF HEADER" }
        require(headerEnd >= 0)
        var cursor = headerEnd + 1
        data class Entry(val record: RinexNavigationRecord, val lines: List<String>, val order: Double?)
        val entries = parsed.records.map { record ->
            val count = 1 + record.orbit.size
            val text = lines.subList(cursor, cursor + count).toList()
            cursor += count
            val order = when (record.system) {
                RinexNavigationSystem.GPS, RinexNavigationSystem.GALILEO, RinexNavigationSystem.BEIDOU,
                RinexNavigationSystem.QZSS, RinexNavigationSystem.IRNSS -> {
                    val week = record.orbit.getOrNull(4)?.getOrNull(2)
                    val seconds = record.orbit.getOrNull(6)?.getOrNull(0)
                    if (week != null && week >= 0 && week <= Int.MAX_VALUE && week == week.toInt().toDouble() &&
                        seconds != null && seconds >= -604800 && seconds < 1209600) week * 604800 + seconds else null
                }
                // State-vector records use their native UTC reference epoch; no inferred ±3h.
                RinexNavigationSystem.GLONASS, RinexNavigationSystem.SBAS -> record.epoch.let {
                    it.date.toEpochDay() * 86400.0 + it.hour * 3600 + it.minute * 60 + it.second
                }
            }
            Entry(record, text, order)
        }
        val selected = entries.groupBy { entry ->
            val r = entry.record
            // Galileo I/NAV and F/NAV describe different clocks/health; retain both histories.
            Triple(r.system, r.svid, if (r.system == RinexNavigationSystem.GALILEO) r.orbit[4][1] else null)
        }.values.flatMap { history ->
            val known = history.filter { it.order != null }.map { it.order!! }.distinct().sortedDescending()
            val accepted = known.take(uploadsPerIdentity).toSet()
            history.filter { it.order == null || it.order in accepted }
        }.toSet()
        val snapshot = (lines.take(headerEnd + 1) + entries.filter { it in selected }.flatMap { it.lines })
            .joinToString("\n", postfix = "\n")
        val resource = RinexNavigationResource.import(snapshot, sourceUrl, downloadedAtMillis)
        RinexNavigationResource.encodeWire(resource) // No silent truncation to a protocol budget.
        return resource
    }
}
