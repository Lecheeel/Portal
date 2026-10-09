package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.playback.*
import com.system.location.service.core.scenario.*
import org.junit.Assert.*
import org.junit.Test
import net.sf.geographiclib.Geodesic

class MotionModelTest {
    private val points = listOf(Wgs84(0.0, 0.0), Wgs84(0.0, 0.0001), Wgs84(0.0001, 0.0001), Wgs84(0.0001, 0.001))
    private fun engine(mode: RouteMode = RouteMode.ONCE, route: List<Wgs84> = points) = PlaybackEngine(Scenario("s", "s", route = Route("r", "r", route), mode = mode, profile = MovementProfile(speedMps = 15.0, smoothMotion = true)))
    @Test fun smoothMovementUsesActualTimeAndHonorsAccelerationAndCornerSpeed() {
        val a = engine(); val b = engine()
        a.tick(0, 0); b.tick(0, 0)
        var previousSpeed = 0f
        for (i in 1..100) {
            val frame = b.tick(i * 100_000_000L, i * 100L)
            assertTrue(frame.sample.speed <= 15)
            assertTrue(kotlin.math.abs(frame.sample.speed - previousSpeed) <= 0.151)
            previousSpeed = frame.sample.speed
        }
        val irregular = a.tick(10_000_000_000, 10_000)
        val regular = b.tick(10_000_000_000, 10_000)
        assertEquals(regular.sample.longitude, irregular.sample.longitude, 1e-12)
        assertEquals(regular.sample.latitude, irregular.sample.latitude, 1e-12)
        assertTrue(irregular.sample.speed < 15)
        val end = a.tick(1_000_000_000_000, 1_000_000)
        assertTrue(end.completed); assertEquals(points.last(), end.sample.coordinate)
    }
    @Test fun changingSpeedAndZeroRetainProgressAndReverseDirection() {
        val e = engine(RouteMode.PING_PONG)
        e.tick(0, 0)
        var frame = e.tick(0, 0)
        var i = 0L
        while (frame.sample.bearing < 180 && i < 1000) { i++; frame = e.tick(i * 1_000_000_000, i * 1000) }
        assertTrue(frame.sample.bearing >= 180)
        e.setSpeed(0.0)
        val stopped = e.tick((i + 50) * 1_000_000_000, (i + 50) * 1000)
        assertEquals(frame.sample.coordinate, stopped.sample.coordinate); assertEquals(0f, stopped.sample.speed)
        e.setSpeed(8.0)
        val same = e.tick((i + 50) * 1_000_000_000, (i + 50) * 1000)
        assertEquals(stopped.sample.longitude, same.sample.longitude, 1e-12)
        assertTrue(same.sample.bearing >= 180)
        e.pause(); e.tick((i + 100) * 1_000_000_000, (i + 100) * 1000)
        e.resume((i + 200) * 1_000_000_000)
        assertEquals(same.sample.coordinate, e.tick((i + 200) * 1_000_000_000, (i + 200) * 1000).sample.coordinate)
    }
    @Test fun duplicatesTwoPointRoutesAndLoopRemainFinite() {
        for (mode in RouteMode.entries) {
            val e = engine(mode, listOf(points[0], points[0], points[1], points[1]))
            e.tick(0, 0)
            val frame = e.tick(10_000_000_000, 10_000)
            assertTrue(frame.progress.isFinite()); assertTrue(frame.sample.speed.isFinite())
            assertEquals(mode == RouteMode.ONCE, frame.completed)
        }
    }
    @Test fun extremelySlowSpeedsAndSpeedChangesDoNotProduceNanOrResetProgress() {
        val e = engine()
        e.tick(0, 0); val before = e.tick(10_000_000_000, 10000)
        e.setSpeed(0.00001)
        val same = e.tick(10_000_000_000, 10000)
        assertEquals(before.progress, same.progress, 1e-12)
        val after = e.tick(20_000_000_000, 20000)
        assertTrue(after.progress.isFinite()); assertTrue(after.sample.speed <= 0.00001f)
        assertTrue(after.progress > before.progress)
    }
    @Test fun joystickHasDeadZoneFiniteCenterAndFineResponse() {
        assertEquals(0.0, JoystickInput.evaluate(0.0, 0.0, 100.0).strength, 0.0)
        assertEquals(0.0, JoystickInput.evaluate(5.0, 0.0, 100.0).strength, 0.0)
        val half = JoystickInput.evaluate(50.0, 0.0, 100.0)
        assertEquals(90.0, half.bearing, 0.0); assertTrue(half.strength in 0.0..0.5)
        assertEquals(1.0, JoystickInput.evaluate(200.0, 0.0, 100.0).strength, 0.0)
        val e = PlaybackEngine(Scenario("p", "p", point = points[0], profile = MovementProfile(speedMps = 10.0)))
        e.tick(0, 0); e.setMotion(90.0, true, 0.25)
        val moved = e.tick(1_000_000_000, 1000).sample
        assertEquals(2.5, Geodesic.WGS84.Inverse(0.0, 0.0, moved.latitude, moved.longitude).s12, 1e-5)
        assertEquals(2.5f, moved.speed)
    }
    @Test fun orbitUsesIndependentPeriodAndFollowsReleasedPosition() {
        val e = PlaybackEngine(Scenario("p", "p", point = points[0], profile = MovementProfile(speedMps = 10.0)))
        e.setOrbit(true, 0.2, 20.0); e.tick(0, 0)
        e.setMotion(90.0, true); val moved = e.tick(10_000_000_000, 10000).sample
        e.setMotion(90.0, false)
        val orbit = e.tick(11_000_000_000, 11000).sample
        assertEquals(0.2, Geodesic.WGS84.Inverse(moved.latitude, moved.longitude, orbit.latitude, orbit.longitude).s12, 0.002)
        assertEquals((2 * Math.PI * 0.2 / 20).toFloat(), orbit.speed)
    }
}
