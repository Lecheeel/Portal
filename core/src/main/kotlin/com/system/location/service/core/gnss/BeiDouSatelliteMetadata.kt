package com.system.location.service.core.gnss

import java.time.LocalDate
import java.time.LocalDateTime

data class BeiDouSatelliteIdentity(val svn: String, val block: String, val orbitType: BeiDouOrbitType?)

/**
 * IGS Metadata SINEX 1.10 §§2.2/2.4, Table 1. PRNs are time-varying assignments, not
 * orbit classes. Epochs remain calendar labels: this parser does not invent their time scale.
 * An adapter must supply resolved labels (or a conservative closed uncertainty interval).
 * Catalog membership is NOT signal health, availability of navigation data or orbital validity.
 * https://files.igs.org/pub/resource/working_groups/multi_gnss/Metadata_SINEX_1.10.pdf
 */
class BeiDouSatelliteMetadata private constructor(
    val creationLabel: String,
    val identities: Map<String, BeiDouSatelliteIdentity>,
    private val assignments: List<Assignment>,
) {
    private data class Assignment(val svn: String, val prn: Int, val from: LocalDateTime, val until: LocalDateTime?)

    /** Reject gaps, overlaps, unknown blocks and uncertainty crossing a reassignment boundary. */
    fun identityAt(prn: Int, earliestLabel: LocalDateTime, latestLabel: LocalDateTime = earliestLabel): BeiDouSatelliteIdentity? {
        require(prn in 1..63 && !latestLabel.isBefore(earliestLabel))
        val intersecting = assignments.filter { it.prn == prn && !it.from.isAfter(latestLabel) &&
            (it.until == null || it.until.isAfter(earliestLabel)) }
        val match = intersecting.singleOrNull() ?: return null
        if (match.from.isAfter(earliestLabel) || (match.until != null && !match.until.isAfter(latestLabel))) return null
        return identities[match.svn]?.takeIf { it.orbitType != null }
    }

    companion object {
        private val orbitTypes = mapOf(
            "BDS-2G" to BeiDouOrbitType.GEO, "BDS-3G" to BeiDouOrbitType.GEO,
            "BDS-2I" to BeiDouOrbitType.IGSO, "BDS-3I" to BeiDouOrbitType.IGSO,
            "BDS-3SI-CAST" to BeiDouOrbitType.IGSO, "BDS-3SI-SECM" to BeiDouOrbitType.IGSO,
            "BDS-2M" to BeiDouOrbitType.MEO, "BDS-3SM-CAST" to BeiDouOrbitType.MEO,
            "BDS-3SM-SECM" to BeiDouOrbitType.MEO, "BDS-3M-CAST" to BeiDouOrbitType.MEO,
            "BDS-3M-SECM-A" to BeiDouOrbitType.MEO, "BDS-3M-SECM-B" to BeiDouOrbitType.MEO,
        )
        private fun epoch(label: String): LocalDateTime {
            require(label.matches(Regex("[0-9]{4}:[0-9]{3}:[0-9]{5}")))
            val year = label.substring(0, 4).toInt()
            val day = label.substring(5, 8).toInt()
            val seconds = label.substring(9).toInt()
            require(year in 1..9999 && seconds in 0..86399)
            require(day in 1..LocalDate.of(year, 1, 1).lengthOfYear())
            return LocalDate.ofYearDay(year, day).atStartOfDay().plusSeconds(seconds.toLong())
        }

        fun parse(text: String): BeiDouSatelliteMetadata {
            require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Satellite metadata too large" }
            require(text.all { it == '\r' || it == '\n' || it in ' '..'~' })
            val lines = text.lines()
            val header = lines.first().trim().split(Regex("\\s+"))
            require(header.size >= 5 && header[0] == "%=SNX" && header[1] == "2.02") { "Unsupported metadata SINEX header" }
            val creation = header[3]
            require(creation.matches(Regex("[0-9]{2}:[0-9]{3}:[0-9]{5}")))
            val identities = linkedMapOf<String, BeiDouSatelliteIdentity>()
            val assignments = mutableListOf<Assignment>()
            val seen = mutableSetOf<String>()
            var block: String? = null
            var ended = false
            for (line in lines.drop(1)) {
                require(line.length <= 256)
                if (ended) { require(line.isBlank()); continue }
                when {
                    line.startsWith("%ENDSNX") -> { require(block == null && line.trim() == "%ENDSNX"); ended = true }
                    line.startsWith("+") -> {
                        require(block == null)
                        block = line.drop(1).trim()
                        require(seen.add(block)) { "Duplicate metadata block" }
                    }
                    line.startsWith("-") -> { require(block != null && line.drop(1).trim() == block); block = null }
                    line.startsWith(" C") && block in setOf("SATELLITE/IDENTIFIER", "SATELLITE/PRN") -> {
                        require(line.length >= 39 && line[5] == ' ')
                        val svn = line.substring(1, 5)
                        require(svn.matches(Regex("C[0-9]{3}")))
                        if (block == "SATELLITE/IDENTIFIER") {
                            require(line[22] == ' ')
                            val name = line.substring(23, 38).trim()
                            require(name.isNotEmpty() && !identities.containsKey(svn))
                            identities[svn] = BeiDouSatelliteIdentity(svn, name, orbitTypes[name])
                        } else {
                            require(line[20] == ' ' && line[35] == ' ' && line[36] == 'C')
                            val from = epoch(line.substring(6, 20))
                            val endLabel = line.substring(21, 35)
                            val until = if (endLabel == "0000:000:00000") null else epoch(endLabel)
                            val prn = line.substring(37, 39).toInt()
                            require(prn in 1..63 && (until == null || until.isAfter(from)))
                            assignments += Assignment(svn, prn, from, until)
                        }
                    }
                }
            }
            require(ended && block == null && seen.containsAll(listOf("SATELLITE/IDENTIFIER", "SATELLITE/PRN")))
            require(identities.isNotEmpty() && assignments.isNotEmpty() && assignments.all { it.svn in identities })
            return BeiDouSatelliteMetadata(creation, identities.toMap(), assignments.distinct())
        }
    }
}
