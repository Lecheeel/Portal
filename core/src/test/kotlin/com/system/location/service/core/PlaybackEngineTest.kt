package com.system.location.service.core

import com.system.location.service.core.geo.*
import com.system.location.service.core.scenario.*
import com.system.location.service.core.playback.PlaybackEngine
import org.junit.Assert.*
import org.junit.Test
import net.sf.geographiclib.Geodesic

class PlaybackEngineTest {
    private val points = listOf(Wgs84(0.0, 0.0), Wgs84(0.0, 0.001), Wgs84(0.001, 0.001))
    private fun engine(mode: RouteMode = RouteMode.ONCE, route: List<Wgs84> = points) =
        PlaybackEngine(Scenario("s", "场景", route = Route("r", "路线", route), mode = mode,
            profile = MovementProfile(speedMps = 10.0)))

    @Test fun irregularTicksTravelAccordingToActualElapsedTime() {
        val slow = engine(); val fast = engine()
        slow.tick(0, 0); fast.tick(0, 0)
        listOf(50L, 150L, 190L, 500L, 900L).forEach { fast.tick(it * 1_000_000, it) }
        val a = slow.tick(1_000_000_000, 1000)
        val b = fast.tick(1_000_000_000, 1000)
        assertEquals(a.sample.latitude, b.sample.latitude, 1e-12)
        assertEquals(a.sample.longitude, b.sample.longitude, 1e-12)
        assertEquals(10.0, Geodesic.WGS84.Inverse(0.0, 0.0, a.sample.latitude, a.sample.longitude).s12, 1e-6)
    }

    @Test fun pauseAndResumeDoNotChargePausedTime() {
        val e = engine(); e.tick(0, 0)
        val before = e.tick(1_000_000_000, 1000)
        e.pause()
        val paused = e.tick(50_000_000_000, 50000)
        assertEquals(before.sample.coordinate, paused.sample.coordinate)
        assertEquals(0f, paused.sample.speed)
        e.resume(100_000_000_000)
        assertEquals(before.sample.coordinate, e.tick(100_000_000_000, 100000).sample.coordinate)
        assertTrue(e.tick(101_000_000_000, 101000).progress > before.progress)
    }

    @Test fun onceFinishesExactlyAtTheLastPointAcrossSeveralSegments() {
        val e = engine(); e.tick(0, 0)
        val end = e.tick(1_000_000_000_000, 1_000_000)
        assertTrue(end.completed)
        assertEquals(points.last(), end.sample.coordinate)
        assertEquals(1.0, end.progress, 0.0)
        assertEquals(0f, end.sample.speed)
    }

    @Test fun loopClosesRouteAndPingPongReverses() {
        val loop = engine(RouteMode.LOOP); loop.tick(0, 0)
        assertFalse(loop.tick(1_000_000_000_000, 1_000_000).completed)
        val ping = engine(RouteMode.PING_PONG); ping.tick(0, 0)
        val halfwayBack = (ping.totalDistance * 1.5 / 10 * 1e9).toLong()
        val reverse = ping.tick(halfwayBack, halfwayBack / 1_000_000)
        assertEquals(0.5, reverse.progress, 1e-8)
        assertFalse(reverse.completed)
    }

    @Test fun routeIsFrozenAndLongRoutesRemainValid() {
        val mutable = points.toMutableList()
        val e = engine(route = mutable)
        mutable.clear()
        assertEquals(3, e.scenario.route!!.points.size)
        val long = engine(route = (0..20000).map { Wgs84(0.0, it * 0.00001) })
        long.tick(0, 0)
        assertTrue(long.tick(100_000_000_000, 100000).progress > 0)
        assertThrows(IllegalArgumentException::class.java) { engine(route = listOf(points[0], points[0])) }
    }

    @Test fun stationaryFixNeverJittersAndJoystickUsesActualTime() {
        val e = PlaybackEngine(Scenario("p", "点", point = Wgs84(25.1, 119.1)))
        val a = e.tick(0, 0)
        assertEquals(a.sample.coordinate, e.tick(500_000_000_000, 500000).sample.coordinate)
        e.setMotion(90.0, true)
        val moved = e.tick(501_000_000_000, 501000)
        assertTrue(moved.sample.longitude > a.sample.longitude)
        assertEquals(3.05, Geodesic.WGS84.Inverse(a.sample.latitude, a.sample.longitude,
            moved.sample.latitude, moved.sample.longitude).s12, 1e-5)
    }

    @Test fun invalidProfilesAndScenariosAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MovementProfile(speedMps = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Scenario("s", "name") }
        assertThrows(IllegalArgumentException::class.java) { Wgs84(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Route("r", "r", emptyList()) }
    }
}
