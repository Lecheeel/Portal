package com.system.location.service.core.gnss

/**
 * Raw almanac health for targetSlot, which may differ from the satellite broadcasting it.
 * Cn=1 means operational at the almanac upload epoch; Cn=0 means non-operational.
 * This is NOT current service acceptance: immediate Bn/ln and independently associated
 * almanac epoch/applicability evidence are also required (ICD 5.1 §5.3/Table 5.1).
 */
data class GlonassAlmanacHealth(
    val sourceSlot: Int,
    val targetSlot: Int,
    val frameId: Int,
    val stringId: Int,
    val capturedAtMillis: Long,
    val satelliteTypeBits: Int,
    val healthCn: Int,
)

data class GlonassAlmanacHealthResult(val health: GlonassAlmanacHealth?, val reason: String? = null)

/** ICD 5.1 §4.5/Table 4.11, almanac even strings. Does not infer an NA epoch from arrival time. */
object GlonassAlmanacHealthDecoder {
    fun decode(string: GlonassNavigationString): GlonassAlmanacHealthResult {
        fun invalid(reason: String) = GlonassAlmanacHealthResult(null, reason)
        if (string.stringId !in setOf(6, 8, 10, 12, 14)) return invalid("requires an even GLONASS almanac string")
        // Superframe string 74 carries B1/B2/KP, not a fifth almanac entry.
        if (string.frameId == 5 && string.stringId == 14) return invalid("GLONASS superframe string 74 is not almanac health")
        val target = string.unsigned(77, 5).toInt()
        // Table 4.9 defines nA=1..24 for this legacy message format. This is an address-field
        // validity check, not a production satellite catalog or a simulated constellation size.
        if (target !in 1..24) return invalid("GLONASS almanac target outside ICD 5.1 nA range")
        val type = string.unsigned(79, 2).toInt()
        if (type !in 0..1) return invalid("reserved GLONASS almanac satellite type")
        return GlonassAlmanacHealthResult(GlonassAlmanacHealth(string.slot, target, string.frameId,
            string.stringId, string.capturedAtMillis, type, string.unsigned(80, 1).toInt()))
    }
}
