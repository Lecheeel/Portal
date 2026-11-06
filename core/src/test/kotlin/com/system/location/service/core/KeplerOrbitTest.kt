package com.system.location.service.core

import com.system.location.service.core.gnss.DopplerShift
import com.system.location.service.core.gnss.KeplerElements
import com.system.location.service.core.gnss.KeplerOrbit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class KeplerOrbitTest {
    private val circular = KeplerElements(
        semiMajorAxisMeters = 7_000_000.0,
        eccentricity = 0.0,
        inclinationRad = 0.0,
        raanRad = 0.0,
        argumentOfPerigeeRad = 0.0,
        meanAnomalyRad = 0.0,
        epochSeconds = 0.0,
    )

    @Test fun circularOrbitStartsOnXAxis() {
        val position = KeplerOrbit(circular).positionAt(0.0)
        assertEquals(7_000_000.0, position.x, 1.0)
        assertEquals(0.0, position.y, 1.0)
        assertEquals(0.0, position.z, 1.0)
        assertEquals(7_000_000.0, position.radiusMeters, 1.0)
    }

    @Test fun onePeriodReturnsToStart() {
        val orbit = KeplerOrbit(circular)
        val start = orbit.positionAt(0.0)
        val end = orbit.positionAt(orbit.orbitalPeriodSeconds())
        assertEquals(start.x, end.x, 10.0)
        assertEquals(start.y, end.y, 10.0)
        assertEquals(start.z, end.z, 10.0)
    }

    @Test fun inclinedOrbitHasZComponent() {
        val inclined = circular.copy(inclinationRad = PI / 2)
        val position = KeplerOrbit(inclined).positionAt(0.0)
        assertEquals(0.0, position.z, 1.0)
        val quarter = KeplerOrbit(inclined).positionAt(KeplerOrbit(inclined).orbitalPeriodSeconds() / 4)
        assertTrue(quarter.z > 6_000_000.0)
    }

    @Test fun eccentricOrbitRadiusStaysWithinBounds() {
        val eccentric = circular.copy(eccentricity = 0.2)
        val orbit = KeplerOrbit(eccentric)
        (0..10).forEach { step ->
            val radius = orbit.positionAt(step * orbit.orbitalPeriodSeconds() / 10).radiusMeters
            assertTrue(radius >= 7_000_000.0 * 0.8 - 10)
            assertTrue(radius <= 7_000_000.0 * 1.2 + 10)
        }
    }

    @Test fun dopplerShiftSignFollowsRangeRate() {
        assertTrue(DopplerShift.observedHz(1_575_420_000.0, 100.0) < 1_575_420_000.0)
        assertTrue(DopplerShift.observedHz(1_575_420_000.0, -100.0) > 1_575_420_000.0)
    }

    @Test fun invalidElementsAndTimeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { circular.copy(eccentricity = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { circular.copy(semiMajorAxisMeters = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { KeplerOrbit(circular).positionAt(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { DopplerShift.observedHz(0.0, 1.0) }
    }
}
