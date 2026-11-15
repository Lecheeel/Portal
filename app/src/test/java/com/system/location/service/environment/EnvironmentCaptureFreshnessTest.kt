package com.system.location.service.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentCaptureFreshnessTest {
    private val fix = 90_000_000_000L

    @Test fun acceptsRadioSamplesInsideTheFixWindow() {
        assertTrue(EnvironmentCaptureFreshness.matchesFix(fix, fix))
        assertTrue(EnvironmentCaptureFreshness.matchesFix(fix - 15_000_000_000L, fix))
        assertTrue(EnvironmentCaptureFreshness.matchesFix(fix + 15_000_000_000L, fix))
    }

    @Test fun rejectsOldFutureAndMissingSampleTimes() {
        assertFalse(EnvironmentCaptureFreshness.matchesFix(fix - 15_000_000_001L, fix))
        assertFalse(EnvironmentCaptureFreshness.matchesFix(fix + 15_000_000_001L, fix))
        assertFalse(EnvironmentCaptureFreshness.matchesFix(0L, fix))
        assertFalse(EnvironmentCaptureFreshness.matchesFix(fix, 0L))
    }

    @Test fun convertsPlatformUnitsWithoutOverflow() {
        assertEquals(fix, EnvironmentCaptureFreshness.wifiTimestampNanos(fix / 1_000L))
        assertEquals(fix, EnvironmentCaptureFreshness.cellTimestampNanos(fix / 1_000_000L))
        assertNull(EnvironmentCaptureFreshness.wifiTimestampNanos(Long.MAX_VALUE))
        assertNull(EnvironmentCaptureFreshness.cellTimestampNanos(Long.MAX_VALUE))
    }
}
