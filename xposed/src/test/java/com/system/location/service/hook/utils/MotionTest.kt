package com.system.location.service.hook.utils

import org.junit.Assert.*
import org.junit.Test

class MotionTest {
    @Test fun zeroDistanceDoesNotDrift() {
        repeat(1000) { assertEquals(39.9 to 116.4, FakeLoc.moveLocation(39.9, 116.4, 0.0, 0.0)) }
    }

    @Test fun movementMatchesDistanceWithoutRandomAcceleration() {
        for (bearing in listOf(0.0, 90.0, 180.0, 270.0)) {
            var point = 39.9 to 116.4
            repeat(100) { point = FakeLoc.moveLocation(point.first, point.second, 0.1, bearing) }
            assertEquals(10.0, FakeLoc.haversine(39.9, 116.4, point.first, point.second), 0.001)
        }
    }

    @Test fun readingBearingNeverRotatesIt() {
        FakeLoc.hasBearings = false
        FakeLoc.bearing = 87.0
        repeat(1000) { assertEquals(87.0, FakeLoc.bearing, 0.0) }
    }

    @Test fun movementCrossesDatelineAndRemainsValidNearPoles() {
        for (point in listOf(0.0 to 179.99999, 89.99999 to 0.0, -89.99999 to 0.0)) {
            val moved = FakeLoc.moveLocation(point.first, point.second, 100.0, 90.0)
            assertTrue(moved.first in -90.0..90.0 && moved.second in -180.0..180.0)
            assertEquals(100.0, FakeLoc.haversine(point.first, point.second, moved.first, moved.second), 0.02)
        }
    }
}
