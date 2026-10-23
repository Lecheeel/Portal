package com.system.location.service.ui.diagnostics

import org.junit.Assert.*
import org.junit.Test

class LocationProbeJournalTest {
    @Test fun missingFixIsUnknownAndCannotCountAsNonMock() {
        val journal = LocationProbeJournal()
        journal.receive("lastKnown/gps", null, 100)
        assertEquals(0, journal.sampleCount)
        assertEquals(0, journal.nonMockCount)
        assertTrue(journal.report().contains("无样本（不等于非模拟）"))
    }
    @Test fun cachedAndLiveFixesRetainSourceAgeAndMockEvidence() {
        val journal = LocationProbeJournal()
        val fix = ProbeFix("gps", 25.0, 119.0, true, 1_000_000_000, 123, 3f)
        journal.receive("lastKnown/gps", fix, 6_000_000_000)
        journal.receive("updates/gps", fix.copy(mock = false, elapsedNanos = 7_000_000_000), 6_000_000_000)
        assertEquals(2, journal.sampleCount)
        assertEquals(1, journal.mockCount)
        assertEquals(1, journal.nonMockCount)
        assertTrue(journal.report().contains("ageMs=5000.0"))
        assertTrue(journal.report().contains("ageMs=-1000.0"))
        assertTrue(journal.report().contains("lastKnown/gps"))
        assertTrue(journal.report().contains("updates/gps"))
    }
    @Test fun boundedHistoryRetainsTotalsAndNewestEvidence() {
        val journal = LocationProbeJournal(2)
        journal.message("old", "discard me")
        journal.message("middle", "keep me")
        journal.message("latest", "keep me too")
        assertFalse(journal.report().contains("discard me"))
        assertTrue(journal.report().indexOf("latest") < journal.report().indexOf("middle"))
    }
}
