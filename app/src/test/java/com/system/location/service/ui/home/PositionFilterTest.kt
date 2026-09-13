package com.system.location.service.ui.home

import org.junit.Assert.*
import org.junit.Test

class PositionFilterTest {
    @Test fun stationaryNoiseIsHeldButMovementIsAccepted() {
        val filter = PositionFilter()
        val start = 39.9 to 116.4
        assertEquals(start, filter.accept(start, 1, 25f, 0f))
        assertEquals(start, filter.accept(39.900001 to 116.400001, 2, 25f, 0f))
        val moved = 39.9001 to 116.4001
        assertEquals(moved, filter.accept(moved, 3, 25f, 0f))
        val walking = 39.90011 to 116.40011
        assertEquals(walking, filter.accept(walking, 4, 25f, 1f))
    }

    @Test fun staleAndInvalidFixesCannotPullTheDisplayBack() {
        val filter = PositionFilter()
        filter.accept(39.9 to 116.4, 10, 5f, 0f)
        assertNull(filter.accept(31.2 to 121.5, 9, 5f, 0f))
        assertNull(filter.accept(Double.NaN to 116.4, 11, 5f, 0f))
        assertNull(filter.accept(91.0 to 116.4, 11, 5f, 0f))
    }
}
