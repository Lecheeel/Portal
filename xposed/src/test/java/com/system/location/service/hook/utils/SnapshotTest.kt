package com.system.location.service.hook.utils

import org.junit.Assert.*
import org.junit.Test

class SnapshotTest {
    @Test fun externallyPublishedFixIsSharedWithoutRewritingItsFieldsOrTime() {
        val fix = com.system.location.service.core.location.LocationSample(
            com.system.location.service.core.geo.Wgs84(25.123456789123, 119.123456789123), 82.0, 4f, 8f, 92f, 100, 100)
        FakeLoc.acceptSample(fix)
        assertSame(fix, FakeLoc.snapshot(true, 150, 150))
        assertSame(fix, FakeLoc.snapshot(false, 200, 200))
        assertEquals(fix.coordinate.latitude, FakeLoc.coordinatePair().first, 0.0)
        FakeLoc.updateCoordinates(0.0, 0.0, nowNanos = 201)
        assertFalse(FakeLoc.externallyDriven)
    }
    @Test fun fixedPositionHasNoNoiseAndTimeAlwaysAdvances() {
        FakeLoc.accuracy = 1000f
        FakeLoc.updateCoordinates(39.9, 116.4, nowNanos = 0)
        var previous = 0L
        repeat(1000) {
            val sample = FakeLoc.snapshot(true, it * 100_000_000L, it * 100L)
            assertEquals(39.9, sample.latitude, 0.0)
            assertEquals(116.4, sample.longitude, 0.0)
            assertEquals(0f, sample.speed)
            assertTrue(sample.elapsedNanos > previous)
            assertEquals(it * 100L, sample.timeMillis)
            previous = sample.elapsedNanos
        }
    }

    @Test fun changingTargetImmediatelyInvalidatesThePreviousSample() {
        FakeLoc.updateCoordinates(39.9, 116.4, 3f, 0)
        assertEquals(3f, FakeLoc.snapshot(true, 1, 1).speed)
        FakeLoc.updateCoordinates(31.2, 121.5, nowNanos = 2)
        val updated = FakeLoc.snapshot(false, 2, 2)
        assertEquals(31.2, updated.latitude, 0.0)
        assertEquals(121.5, updated.longitude, 0.0)
        assertEquals(0f, updated.speed)
    }

    @Test fun stoppedMovementExpiresWithoutInventingASpeed() {
        FakeLoc.reportIntervalMs = 100
        FakeLoc.updateCoordinates(39.9, 116.4, 3f, 0)
        assertEquals(3f, FakeLoc.snapshot(true, 100_000_000, 100).speed)
        assertEquals(0f, FakeLoc.snapshot(true, 600_000_000, 600).speed)
    }

    @Test fun readersShareOneSampleUntilTheNextPublication() {
        val now = System.nanoTime()
        FakeLoc.updateCoordinates(39.9, 116.4, nowNanos = now)
        val sample = FakeLoc.snapshot(true, now, 10)
        repeat(100) { assertSame(sample, FakeLoc.snapshot(false, now + it, 10)) }
    }
}
