package com.system.location.service

import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.toLocation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Runs on ART: exercises real Location and elapsedRealtimeNanos, without enabling hooks. */
class LocationSampleTest {
    @Test fun fixedPointNeverJittersEvenWithLargeAccuracy() {
        FakeLoc.accuracy = 1000f
        FakeLoc.bearing = 123.0
        FakeLoc.updateCoordinates(39.9, 116.4)
        repeat(1000) {
            val sample = FakeLoc.snapshot(force = true)
            assertEquals(39.9, sample.latitude, 0.0)
            assertEquals(116.4, sample.longitude, 0.0)
            assertEquals(0f, sample.speed)
            assertEquals(123f, sample.bearing)
        }
    }

    @Test fun eachBroadcastIsFreshAndEveryRecipientGetsTheSameFix() {
        FakeLoc.updateCoordinates(31.2, 121.5)
        var previous = FakeLoc.snapshot(force = true)
        repeat(100) {
            val sample = FakeLoc.snapshot(force = true)
            assertTrue(sample.elapsedNanos > previous.elapsedNanos)
            assertTrue(kotlin.math.abs(System.currentTimeMillis() - sample.timeMillis) < 1000)
            val gps = sample.toLocation("gps")
            val network = sample.toLocation("network")
            assertEquals(gps.latitude, network.latitude, 0.0)
            assertEquals(gps.longitude, network.longitude, 0.0)
            assertEquals(gps.elapsedRealtimeNanos, network.elapsedRealtimeNanos)
            assertEquals(gps.time, network.time)
            assertTrue(gps.hasAccuracy())
            previous = sample
        }
    }

    @Test fun coordinateChangeInvalidatesCachedSampleAndResetsSpeed() {
        FakeLoc.updateCoordinates(39.9, 116.4, 3f)
        val before = FakeLoc.snapshot()
        assertEquals(3f, before.speed)
        FakeLoc.updateCoordinates(31.2, 121.5)
        val after = FakeLoc.snapshot()
        assertEquals(31.2, after.latitude, 0.0)
        assertEquals(121.5, after.longitude, 0.0)
        assertEquals(0f, after.speed)
        assertTrue(after.elapsedNanos > before.elapsedNanos)
    }

    @Test fun simultaneousReadsNeverMixLatitudeAndLongitude() {
        FakeLoc.updateCoordinates(10.0, 20.0)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            repeat(2000) { FakeLoc.updateCoordinates(if (it % 2 == 0) 10.0 else 30.0, if (it % 2 == 0) 20.0 else 40.0) }
        }
        val reader = Thread {
            try {
                repeat(2000) {
                    val sample = FakeLoc.snapshot()
                    assertTrue(sample.latitude == 10.0 && sample.longitude == 20.0 || sample.latitude == 30.0 && sample.longitude == 40.0)
                }
            } catch (t: Throwable) { failure.set(t) }
        }
        writer.start(); reader.start()
        writer.join(); reader.join()
        failure.get()?.let { throw it }
    }
}
