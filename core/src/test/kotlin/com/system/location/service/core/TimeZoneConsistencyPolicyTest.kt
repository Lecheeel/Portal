package com.system.location.service.core

import com.system.location.service.core.network.TimeZoneConsistencyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeZoneConsistencyPolicyTest {
    @Test fun nominalOffsetsFollowLongitude() {
        assertEquals(480, TimeZoneConsistencyPolicy.nominalOffsetMinutes(120.0))
        assertEquals(-300, TimeZoneConsistencyPolicy.nominalOffsetMinutes(-75.0))
        assertEquals(0, TimeZoneConsistencyPolicy.nominalOffsetMinutes(0.0))
        assertEquals(720, TimeZoneConsistencyPolicy.nominalOffsetMinutes(180.0))
        assertEquals(-720, TimeZoneConsistencyPolicy.nominalOffsetMinutes(-180.0))
    }

    @Test fun toleranceMarksNearbyZonesConsistent() {
        assertTrue(TimeZoneConsistencyPolicy.check(121.4737, 480).consistent)
        assertTrue(TimeZoneConsistencyPolicy.check(121.4737, 540, toleranceMinutes = 60).consistent)
        assertFalse(TimeZoneConsistencyPolicy.check(121.4737, 0).consistent)
    }

    @Test fun formatsOffsets() {
        assertEquals("UTC+08:00", TimeZoneConsistencyPolicy.formatOffset(480))
        assertEquals("UTC-05:00", TimeZoneConsistencyPolicy.formatOffset(-300))
        assertEquals("UTC+00:00", TimeZoneConsistencyPolicy.formatOffset(0))
    }
}
