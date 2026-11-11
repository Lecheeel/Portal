package com.system.location.service.core.gnss

import java.time.LocalDate

/** Native calendar labels; not UTC timestamps. GLONASS labels alone use UTC in RINEX 3. */
data class RinexNavigationEpoch(val date: LocalDate, val hour: Int, val minute: Int, val second: Int) {
    init { require(date.year in 1..9999 && hour in 0..23 && minute in 0..59 && second in 0..60) }
}

enum class RinexNavigationSystem(val code: Char, internal val ordinaryRows: Int) {
    GPS('G', 7), GALILEO('E', 7), BEIDOU('C', 7), GLONASS('R', 3),
    QZSS('J', 7), SBAS('S', 3), IRNSS('I', 7);
}

/**
 * Lossless numeric fields including absent/spare fields. Presence is not evidence of health,
 * fit, clock correction, orbit class or propagation suitability; system adapters decide those.
 * In particular Galileo's RINEX week is GPS-aligned, NOT the native GST week.
 */
data class RinexNavigationRecord(
    val system: RinexNavigationSystem,
    val svid: Int,
    val epoch: RinexNavigationEpoch,
    val clock: List<Double?>,
    val orbit: List<List<Double?>>,
)

data class RinexNavigationFile(
    val version: String,
    val timeSystemCorrections: List<String>,
    val leapSecondHeaders: List<String>,
    val records: List<RinexNavigationRecord>,
)

/**
 * Bounded fixed-column RINEX 3.02–3.05 navigation reader, Tables A5–A18.
 * https://files.igs.org/pub/data/format/rinex305.pdf
 * No RINEX 2/4 guessing, whitespace tokenization, week modulo, unit or epoch conversion.
 * A damaged resource is rejected atomically so the resource store can retain its last good file.
 */
object RinexNavigationReader {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_RECORDS = 50_000
    private const val MAX_HEADER_LINES = 2048
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[EeDd][+-]?[0-9]+)?")
    private val firstLine = Regex("([GECRJSI])([0-9]{2}) ([0-9]{4}) ([ 0-9][0-9]) ([ 0-9][0-9]) ([ 0-9][0-9]) ([ 0-9][0-9]) ([ 0-9][0-9])")

    fun read(text: String): RinexNavigationFile {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "RINEX navigation resource exceeds size limit"
        }
        require(text.all { it == '\r' || it == '\n' || it in ' '..'~' }) { "Non-ASCII RINEX content" }
        val lines = text.lineSequence().iterator()
        var lineNumber = 0
        fun next(): String {
            require(lines.hasNext()) { "Truncated RINEX navigation at line ${lineNumber + 1}" }
            val line = lines.next()
            lineNumber++
            require(line.length <= 80) { "RINEX line $lineNumber exceeds 80 columns" }
            return line
        }
        val header = next().padEnd(80)
        require(header.substring(60).trim() == "RINEX VERSION / TYPE" && header[20] == 'N') {
            "Not a RINEX navigation file"
        }
        val version = header.substring(0, 9).trim()
        require(version in listOf("3.02", "3.03", "3.04", "3.05")) { "Unsupported RINEX version $version" }
        val declaredSystem = header[40]
        require(declaredSystem == 'M' || RinexNavigationSystem.entries.any { it.code == declaredSystem }) {
            "Unknown RINEX header satellite system"
        }
        val corrections = mutableListOf<String>()
        val leaps = mutableListOf<String>()
        while (true) {
            require(lineNumber < MAX_HEADER_LINES) { "RINEX header exceeds line limit" }
            val line = next().padEnd(80)
            when (line.substring(60).trim()) {
                "RINEX VERSION / TYPE" -> error("Duplicate RINEX version header")
                "TIME SYSTEM CORR" -> corrections += line.substring(0, 60)
                "LEAP SECONDS" -> leaps += line.substring(0, 60)
                "END OF HEADER" -> break
                "" -> error("Missing RINEX header label at line $lineNumber")
            }
        }
        fun numericFields(line: String, start: Int, count: Int): List<Double?> {
            require(line.drop(start + 19 * count).isBlank()) { "Extra RINEX numeric fields at line $lineNumber" }
            val padded = line.padEnd(start + 19 * count)
            return (0 until count).map { column ->
                val token = padded.substring(start + column * 19, start + (column + 1) * 19).trim()
                if (token.isEmpty()) null else {
                    require(number.matches(token)) { "Invalid RINEX number at line $lineNumber, field ${column + 1}" }
                    token.replace('D', 'E').replace('d', 'e').toDouble().also {
                        require(it.isFinite()) { "Non-finite RINEX number at line $lineNumber" }
                    }
                }
            }
        }
        val records = mutableListOf<RinexNavigationRecord>()
        while (lines.hasNext()) {
            val line = next()
            // A single terminating newline is not another record. Interior empty lines are invalid.
            if (line.isEmpty() && !lines.hasNext()) break
            require(records.size < MAX_RECORDS) { "RINEX record limit exceeded" }
            require(line.length >= 23) { "Missing RINEX satellite epoch at line $lineNumber" }
            val match = requireNotNull(firstLine.matchEntire(line.substring(0, 23))) {
                "Invalid RINEX satellite epoch at line $lineNumber"
            }.groupValues
            val system = RinexNavigationSystem.entries.single { it.code == match[1].single() }
            require(declaredSystem == 'M' || declaredSystem == system.code) { "RINEX header/record system mismatch" }
            val svid = match[2].toInt()
            require(svid in 1..99) { "Invalid RINEX satellite number" }
            val epoch = RinexNavigationEpoch(LocalDate.of(match[3].toInt(), match[4].trim().toInt(),
                match[5].trim().toInt()), match[6].trim().toInt(), match[7].trim().toInt(), match[8].trim().toInt())
            val clock = numericFields(line, 23, 3)
            val rows = system.ordinaryRows + if (system == RinexNavigationSystem.GLONASS && version == "3.05") 1 else 0
            val orbit = (0 until rows).map {
                val continuation = next()
                require(continuation.length >= 4 && continuation.take(4) == "    ") {
                    "Invalid RINEX continuation at line $lineNumber"
                }
                numericFields(continuation, 4, 4)
            }
            records += RinexNavigationRecord(system, svid, epoch, clock, orbit)
        }
        require(records.isNotEmpty()) { "RINEX navigation file contains no records" }
        return RinexNavigationFile(version, corrections.toList(), leaps.toList(), records.toList())
    }
}
