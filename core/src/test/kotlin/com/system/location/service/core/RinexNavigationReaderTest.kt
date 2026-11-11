package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RinexNavigationReaderTest {
    private fun header(version: String = "3.04", system: Char = 'M') =
        "     $version           NAVIGATION DATA     $system".padEnd(60) + "RINEX VERSION / TYPE\n" +
            "GAGP  2.2700987756E-09 1.287858709E-14 432000 2105".padEnd(60) + "TIME SYSTEM CORR\n" +
            "    18".padEnd(60) + "LEAP SECONDS\n" + "".padEnd(60) + "END OF HEADER\n"

    // Layout and adjacent signed D19.12 fields from IGS RINEX 3.05, Tables A8/A9.
    private val galileo = """
E01 2020 05 15 00 00 00-7.311511784792D-04-1.065814103640D-11 0.000000000000D+00
     6.600000000000D+01-2.093750000000D+01 2.814760101310D-09-2.614248883495D+00
    -1.059845089912D-06 1.353232655674D-04 9.920448064804D-06 5.440612745285D+03
     4.320000000000D+05-2.607703208923D-08 2.453152314225D+00-1.862645149231D-08
     9.712686443587D-01 1.290000000000D+02 1.184225012358D+00-5.608090731035D-09
     2.428672591152D-10 5.170000000000D+02 2.105000000000D+03
     3.120000000000D+00 0.000000000000D+00 4.889443516731D-09 5.122274160385D-09
     4.326500000000D+05
""".trimIndent()

    private fun state(system: Char = 'R', extra: Boolean = false): String =
        "${system}01 2020 05 15 00 15 00 1.000000000000D-04 2.000000000000D-12 4.320000000000D+05\n" +
            List(if (extra) 4 else 3) {
                "     1.234567890123D+04-1.234567890123D+00 1.234567890123D-09 0.000000000000D+00"
            }.joinToString("\n")

    @Test fun fixedColumnsPreserveNativeEpochWeekSpareAndUnits() {
        val file = RinexNavigationReader.read(header() + galileo)
        assertEquals("3.04", file.version)
        assertEquals(1, file.timeSystemCorrections.size)
        assertEquals(1, file.leapSecondHeaders.size)
        val record = file.records.single()
        assertEquals(RinexNavigationSystem.GALILEO, record.system)
        assertEquals(LocalDate.of(2020, 5, 15), record.epoch.date)
        assertEquals(-7.311511784792e-4, record.clock[0])
        assertEquals(-1.065814103640e-11, record.clock[1])
        assertEquals(2105.0, record.orbit[4][2]) // RINEX's GPS-aligned week, not GST 1081.
        assertEquals(517.0, record.orbit[4][1])
        assertNull(record.orbit[4][3])
        assertEquals(listOf(432650.0, null, null, null), record.orbit[6])
        assertEquals(5440.612745285, record.orbit[1][3]) // sqrt(m), not m or km.
    }

    @Test fun glonass305ExtraRowDoesNotConsumeNextSatelliteAnd304HasThreeRows() {
        for ((version, extra) in listOf("3.04" to false, "3.05" to true)) {
            val file = RinexNavigationReader.read(header(version) + state(extra = extra) + "\n" + galileo + "\n")
            assertEquals(listOf(RinexNavigationSystem.GLONASS, RinexNavigationSystem.GALILEO), file.records.map { it.system })
            assertEquals(if (extra) 4 else 3, file.records.first().orbit.size)
            assertEquals(-1.234567890123, file.records.first().orbit[0][1])
        }
    }

    @Test fun recognizesEveryRinex3NavigationSystemWithoutGuessingUnsupportedMessageLayouts() {
        val records = listOf('G', 'E', 'C', 'J', 'I').map { galileo.replaceFirst('E', it) } + state() + state('S')
        val file = RinexNavigationReader.read(header() + records.joinToString("\n"))
        assertEquals(RinexNavigationSystem.entries.toSet(), file.records.map { it.system }.toSet())
        assertEquals(7, file.records.size)
    }

    @Test fun malformedOrUnsupportedResourcesAreRejectedAsAWhole() {
        val badFiles = listOf(
            header("4.00") + galileo,
            header(system = 'G') + galileo,
            header() + galileo.substringBeforeLast('\n'), // incomplete record
            header() + galileo.replace("5.440612745285D+03", "5.440612745285D999"),
            header() + galileo.replace("E01 2020 05 15", "E01 2020 02 30"),
            header() + galileo.replaceFirst("    -", "   E-"),
            header() + galileo.replace("E01", "X01"),
            header() + galileo.replace("E01", "E00"),
            header() + galileo + "\n\n" + state(),
            header() + galileo + "\n" + "x".repeat(81),
            header() + galileo.replace("D+03", "D+0\t"),
        )
        for ((index, file) in badFiles.withIndex()) assertThrows("bad fixture $index", Exception::class.java) {
            RinexNavigationReader.read(file)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RinexNavigationReader.read(" ".repeat(RinexNavigationReader.MAX_BYTES + 1))
        }
    }
}
