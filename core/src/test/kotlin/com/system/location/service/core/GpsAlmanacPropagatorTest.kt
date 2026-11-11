package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class GpsAlmanacPropagatorTest {
    private val axis = 26_560_000.0
    private val epoch = GpsTime(2444, 0.0)
    private val circular = GpsYumaAlmanac(1, 0, epoch, 0.0, 0.0, 0.0, sqrt(axis),
        0.0, 0.0, 0.0, 0.0, 0.0)

    @Test fun circularEquatorialOrbitMatchesClosedFormAcrossWeekBoundary() {
        val motion = sqrt(GpsBroadcastPropagator.EARTH_MU / axis.pow(3))
        val earth = GpsBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND
        for (seconds in listOf(-100.0, 0.0, 100.0, 43200.0, 604900.0)) {
            val result = GpsAlmanacPropagator.position(circular, epoch.plusSeconds(seconds), 700000.0).position!!
            val angle = (motion - earth) * seconds
            assertEquals(axis * cos(angle), result.xMeters, 1e-6)
            assertEquals(axis * sin(angle), result.yMeters, 1e-6)
            assertEquals(0.0, result.zMeters, 0.0)
        }
    }

    @Test fun eccentricPerigeeAndApogeeMatchAnalyticRadii() {
        for (eccentricity in listOf(0.001, 0.01, 0.03)) {
            val perigee = circular.copy(eccentricity = eccentricity)
            val apogee = perigee.copy(meanAnomalyRad = PI)
            val p = GpsAlmanacPropagator.position(perigee, epoch, 0.0).position!!
            val a = GpsAlmanacPropagator.position(apogee, epoch, 0.0).position!!
            assertEquals(axis * (1 - eccentricity), p.xMeters, 1e-7)
            assertEquals(-axis * (1 + eccentricity), a.xMeters, 1e-7)
            assertEquals(0.0, a.yMeters, 1e-7)
        }
    }

    @Test fun inclinationIsAbsoluteAndNodeIncludesReferenceTimeRotation() {
        val tow = 503808.0
        val sample = circular.copy(referenceTime = GpsTime(2444, tow), inclinationRad = PI / 2,
            meanAnomalyRad = PI / 2)
        val pole = GpsAlmanacPropagator.position(sample, sample.referenceTime, 0.0).position!!
        assertEquals(axis, pole.zMeters, 1e-7)
        assertEquals(0.0, pole.xMeters, 1e-7)
        assertEquals(0.0, pole.yMeters, 1e-7)
        val equator = sample.copy(meanAnomalyRad = 0.0)
        val result = GpsAlmanacPropagator.position(equator, equator.referenceTime, 0.0).position!!
        val angle = -GpsBroadcastPropagator.EARTH_ROTATION_RAD_PER_SECOND * tow
        assertEquals(axis * cos(angle), result.xMeters, 1e-7)
        assertEquals(axis * sin(angle), result.yMeters, 1e-7)
    }

    @Test fun unhealthyAndOldResourcesNeverAliasIntoCurrentWeek() {
        assertNull(GpsAlmanacPropagator.position(circular.copy(health = 1), epoch, 0.0).position)
        assertNotNull(GpsAlmanacPropagator.position(circular, epoch.plusSeconds(100.0), 100.0).position)
        assertNull(GpsAlmanacPropagator.position(circular, epoch.plusSeconds(100.001), 100.0).position)
        assertNull(GpsAlmanacPropagator.position(circular, epoch.plusSeconds(-100.001), 100.0).position)
        assertNull(GpsAlmanacPropagator.position(circular, GpsTime(epoch.week + 1024, 0.0), 604800.0).position)
        assertThrows(IllegalArgumentException::class.java) {
            GpsAlmanacPropagator.position(circular, epoch, Double.POSITIVE_INFINITY)
        }
    }
}
