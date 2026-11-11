package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GpsYumaParserTest {
    // Synthetic parameters in the published CelesTrak Yuma format; not handset observations.
    private val record = """
        ******** Week 396 almanac for PRN-01 ********
        ID:                         01
        Health:                     000
        Eccentricity:               0.002
        Time of Applicability(s):   503808.0000
        Orbital Inclination(rad):   0.955
        Rate of Right Ascen(r/s):  -0.8E-008
        SQRT(A)  (m 1/2):           5153.68
        Right Ascen at Week(rad):  -1.37
        Argument of Perigee(rad):   0.14
        Mean Anom(rad):            -1.18
        Af0(s):                     0.1E-003
        Af1(s/s):                  -0.7E-011
        week:                      396
    """.trimIndent()

    @Test fun resolvesTruncatedWeekOnceAndPreservesAbsoluteInclination() {
        val parsed = GpsYumaParser.parse(record, 2444).single()
        assertEquals(GpsTime(2444, 503808.0), parsed.referenceTime)
        assertEquals(0.955, parsed.inclinationRad, 0.0)
        assertEquals(-8e-9, parsed.ascendingNodeRateRadPerSecond, 1e-24)
        assertEquals(0, parsed.health)
        assertEquals(1, parsed.svid)
        assertEquals(parsed, GpsYumaParser.parse(record.replace("E-", "D-"), 2444).single())
    }

    @Test fun fullWeeksAndRolloverAreUnambiguousWithExternalAnchor() {
        assertEquals(2048, GpsYumaParser.parse(record.replace("week:                      396",
            "week: 0"), 2047).single().referenceTime.week)
        assertEquals(2444, GpsYumaParser.parse(record.replace("week:                      396",
            "week: 2444"), 2444).single().referenceTime.week)
        assertThrows(IllegalArgumentException::class.java) { GpsYumaParser.parse(record, 1932) }
        assertThrows(IllegalArgumentException::class.java) { GpsYumaParser.parse(record, -1) }
    }

    @Test fun rejectsPartialDuplicateUnknownAndNonFiniteRecords() {
        val invalid = listOf("", record.substringBefore("Af1(s/s)"), "$record\n$record",
            "$record\nHealth: 0", record.replace("Health:", "Mystery:"),
            record.replace("0.955", "NaN"), record.replace("503808.0000", "604800"),
            record.replace("0.002", "-0.1"), record.replace("01\nHealth", "33\nHealth"),
            record.replace("0.8E-008", "Infinity"))
        for (text in invalid) assertThrows(IllegalArgumentException::class.java) {
            GpsYumaParser.parse(text, 2444)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsYumaParser.parse(" ".repeat(GpsYumaParser.MAX_BYTES + 1), 2444)
        }
    }

    @Test fun entireUpdateRejectsMixedEpochsButDoesNotDiscardUnhealthyRecords() {
        val second = record.replace("ID:                         01", "ID: 02")
            .replace("Health:                     000", "Health: 63")
        val parsed = GpsYumaParser.parse("$record\n\n$second", 2444)
        assertEquals(listOf(1, 2), parsed.map { it.svid })
        assertEquals(63, parsed.last().health)
        assertThrows(IllegalArgumentException::class.java) {
            GpsYumaParser.parse("$record\n\n${second.replace("503808.0000", "409600")}", 2444)
        }
    }
}
