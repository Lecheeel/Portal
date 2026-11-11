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
            val order = RinexNavigationUploadOrder.seconds(record)
            Entry(record, text, order)
        }
        val selected = entries.groupBy { RinexNavigationUploadOrder.identity(it.record) }.values.flatMap { history ->
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
