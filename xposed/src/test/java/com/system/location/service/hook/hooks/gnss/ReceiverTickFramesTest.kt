package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverTickFramesTest {
    @Test fun bothTransportsAndAllRecipientsShareOnePublicationForEachRoute() {
        var calls = 0L
        val frames = ReceiverTickFrames { point ->
            PublishedGnssFrame(++calls, ReceiverGnssFrame(1000, point ?: Wgs84(31.0, 121.0),
                0.0, emptyList(), GnssGeometryResult(null), ReceiverGnssSource.STATISTICAL_REPLAY), 0.0, 0.0)
        }
        val global = frames.forPoint(null)
        repeat(20) { assertSame(global, frames.forPoint(null)) }
        val point = Wgs84(40.0, 116.0)
        val fixed = frames.forPoint(point)
        assertNotSame(global, fixed)
        repeat(20) { assertSame(fixed, frames.forPoint(point)) }
        assertEquals(2L, calls)
    }

    @Test fun missingPublicationIsAlsoStableForOneDeliveryTick() {
        var calls = 0
        val frames = ReceiverTickFrames { calls++; null }
        repeat(20) { assertNull(frames.forPoint(null)) }
        assertEquals(1, calls)
    }
}
