package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GlonassReferenceEpochTest {
    private fun c(date: String, second: Double) = GlonassCalendarCoordinate(LocalDate.parse(date), second)
    private val domain = GlonassTimeDomain("synthetic-continuous-calendar-evidence", c("2023-12-30", 0.0), c("2024-01-03", 0.0))

    @Test fun previousAndNextDayResolutionRetainsFullYearAndFourYearBoundary() {
        val before = GlonassReferenceEpoch.resolve(1, 85500, c("2024-01-01", 0.0), domain)
        assertNotNull(before.reason, before.time)
        assertEquals(c("2023-12-31", 85500.0), before.time!!.coordinate)
        val after = GlonassReferenceEpoch.resolve(1461, 900, c("2023-12-31", 86370.0), domain)
        assertNotNull(after.reason, after.time)
        val afterTime = after.time!!
        assertEquals(c("2024-01-01", 900.0), afterTime.coordinate)
        // Resolving a label does not assert the target transmission is within the <900s orbit interval.
        assertEquals(930.0, afterTime.secondsSince(domain.at(c("2023-12-31", 86370.0))!!)!!, 0.0)
    }

    @Test fun rejectsHalfDayAmbiguityWrongDateMissingCoverageAndInvalidLabels() {
        assertNull(GlonassReferenceEpoch.resolve(1, 900, c("2024-01-01", 44100.0), domain).time)
        assertNull(GlonassReferenceEpoch.resolve(2, 85500, c("2024-01-01", 0.0), domain).time)
        for (tb in listOf(0, 899, 901, 86400))
            assertNull(GlonassReferenceEpoch.resolve(1, tb, c("2024-01-01", 0.0), domain).time)
        val short = GlonassTimeDomain("coverage-without-tb", c("2024-01-01", 0.0), c("2024-01-02", 0.0))
        assertNull(GlonassReferenceEpoch.resolve(1, 85500, c("2024-01-01", 0.0), short).time)
        assertNull(GlonassReferenceEpoch.resolve(1, 900, c("2024-01-03", 0.0), domain).time)
        assertNull(GlonassReferenceEpoch.resolve(0, 900, c("2024-01-01", 0.0), domain).time)
    }
}
