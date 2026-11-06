package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.OrbitalGnssSkyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitalGnssSkyModelTest {
    private val observer = Wgs84(31.2304, 121.4737)
    private val model = OrbitalGnssSkyModel()

    @Test fun frameIsDeterministic() {
        val first = model.frame(1_790_000_000_000, observer, 80.0)
        val second = model.frame(1_790_000_000_000, observer, 80.0)
        assertEquals(first, second)
    }

    @Test fun frameContainsFiniteSatellites() {
        val frame = model.frame(1_790_000_000_000, observer, 80.0)
        assertEquals(5, frame.satellites.size)
        frame.satellites.forEach {
            assertTrue(it.elevationDeg in -90f..90f)
            assertTrue(it.azimuthDeg in 0f..360f)
            assertTrue(it.cn0DbHz in 0f..60f)
        }
        assertEquals(frame.satellites.map { it.constellation to it.svid }.distinct().size, frame.satellites.size)
        assertTrue(frame.hdop > 0f && frame.vdop > 0f && frame.pdop > 0f)
    }

    @Test fun usedInFixFollowsElevation() {
        val frame = model.frame(1_790_000_000_000, observer, 80.0)
        frame.satellites.forEach { assertEquals(it.elevationDeg >= 10f, it.usedInFix) }
    }

    @Test fun altitudeChangesGeometry() {
        val low = model.frame(1_790_000_000_000, observer, 0.0)
        val high = model.frame(1_790_000_000_000, observer, 2_000.0)
        assertTrue(low.satellites != high.satellites)
    }

    @Test fun invalidInputIsRejected() {
        assertThrows { model.frame(-1, observer, 80.0) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
