package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverTickFramesTest {
    @Test fun bothTransportsAndAllRecipientsShareOnePublicationForEachRoute() {
        var calls = 0L
        val frames = ReceiverTickFrames { route ->
            PublishedGnssFrame(++calls, ReceiverGnssFrame(1000, (route as? GnssFrameRoute.FixedPoint)?.point ?: Wgs84(31.0, 121.0),
                0.0, emptyList(), GnssGeometryResult(null), ReceiverGnssSource.STATISTICAL_REPLAY), 0.0, 0.0)
        }
        val global = frames.forRoute(GnssFrameRoute.Global)
        repeat(20) { assertSame(global, frames.forRoute(GnssFrameRoute.Global)) }
        val point = GnssFrameRoute.FixedPoint(Wgs84(40.0, 116.0))
        val fixed = frames.forRoute(point)
        assertNotSame(global, fixed)
        repeat(20) { assertSame(fixed, frames.forRoute(point)) }
        val scenario = GnssFrameRoute.Scenario("route-a")
        val routed = frames.forRoute(scenario)
        assertNotSame(global, routed)
        repeat(20) { assertSame(routed, frames.forRoute(scenario)) }
        assertEquals(3L, calls)
    }

    @Test fun missingPublicationIsAlsoStableForOneDeliveryTick() {
        var calls = 0
        val frames = ReceiverTickFrames { calls++; null }
        repeat(20) { assertNull(frames.forRoute(GnssFrameRoute.Global)) }
        assertEquals(1, calls)
    }
}
