package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GalileoServiceValidityTest {
    @Test fun explicitFullGstBoundsRemainIndependentAcrossWeekBoundary() {
        val broadcast = GalileoTime(100, 604_799.0)
        val validity = GalileoServiceValidity(1, GalileoClockReference.INAV_E1_E5B, broadcast,
            broadcast.plusSeconds(-1.0), broadcast.plusSeconds(2.0))
        assertTrue(validity.contains(broadcast.plusSeconds(-1.0)))
        assertTrue(validity.contains(GalileoTime(101, 0.0)))
        assertFalse(validity.contains(broadcast.plusSeconds(-1.001)))
        assertFalse(validity.contains(GalileoTime(101, 1.0)))
        assertFalse(validity.contains(GalileoTime(99, 604_799.0)))
        assertEquals(validity, Json.decodeFromString<GalileoServiceValidity>(Json.encodeToString(validity)))
    }

    @Test fun serviceSnapshotMustFallInsideItsOwnIndependentInterval() {
        val time = GalileoTime(100, 10_000.0)
        val valid = GalileoServiceValidity(1, GalileoClockReference.FNAV_E1_E5A, time, time, time.plusSeconds(1.0))
        assertThrows(IllegalArgumentException::class.java) { valid.copy(from = time.plusSeconds(0.1)) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(until = time) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(svid = 0) }
    }
}
