package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.CatalogGnssSkyModel
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GnssSkyModelTest {
    private val model = CatalogGnssSkyModel()
    private val observer = Wgs84(31.2304, 121.4737)
    private val atMillis = 1_790_000_000_000L

    @Test
    fun sameTimeAndObserverAreDeterministic() {
        val first = model.frame(atMillis, observer, 80.0)
        val second = model.frame(atMillis, observer, 80.0)
        assertEquals(first, second)
        assertEquals(atMillis, first.generatedAtMillis)
    }

    @Test
    fun frameContainsMultipleConstellations() {
        val frame = model.frame(atMillis, observer, 80.0)
        val constellations = frame.satellites.map { it.constellation }.toSet()
        assertTrue(constellations.contains(Constellation.GPS))
        assertTrue(constellations.contains(Constellation.BEIDOU))
        assertTrue(constellations.contains(Constellation.GLONASS))
        assertTrue(constellations.contains(Constellation.GALILEO))
        assertTrue(frame.satellites.size >= 20)
        assertTrue(frame.usedInFix.isNotEmpty())
    }

    @Test
    fun satelliteSetIsStableWhileTimeMoves() {
        val first = model.frame(atMillis, observer, 80.0)
        val later = model.frame(atMillis + 30_000, observer, 80.0)
        assertEquals(
            first.satellites.map { it.constellation to it.svid },
            later.satellites.map { it.constellation to it.svid },
        )
        assertEquals(first.countFor(Constellation.GPS), later.countFor(Constellation.GPS))
    }

    @Test
    fun satellitesRespectBoundsAndHaveNoDuplicates() {
        val frame = model.frame(atMillis, observer, 80.0)
        val keys = frame.satellites.map { it.constellation to it.svid }
        assertEquals(keys.size, keys.distinct().size)
        frame.satellites.forEach { satellite ->
            assertTrue(satellite.svid in 1..999)
            assertTrue(satellite.elevationDeg in -90f..90f)
            assertTrue(satellite.azimuthDeg in 0f..360f)
            assertTrue(satellite.cn0DbHz in 0f..GnssFrame.MAX_CN0_DB_HZ)
            assertTrue(satellite.carrierHz != null && satellite.carrierHz!! > 1e9f)
        }
        assertTrue(frame.hdop > 0f && frame.vdop > 0f && frame.pdop > 0f)
        assertTrue(frame.pdop >= frame.hdop)
    }

    @Test
    fun azimuthRotatesSlowlyAndElevationEvolves() {
        val first = model.frame(atMillis, observer, 80.0)
        val later = model.frame(atMillis + 60_000, observer, 80.0)
        val firstGps = first.satellites.first { it.constellation == Constellation.GPS }
        val laterGps = later.satellites.first { it.constellation == Constellation.GPS }
        assertEquals(firstGps.svid, laterGps.svid)
        assertNotEquals(firstGps.azimuthDeg, laterGps.azimuthDeg)
        assertTrue(abs(firstGps.azimuthDeg - laterGps.azimuthDeg) < 2f)
    }

    @Test
    fun cn0CorrelatesWithElevation() {
        val frame = model.frame(atMillis, observer, 80.0)
        val elevations = frame.satellites.map { it.elevationDeg.toDouble() }
        val cn0s = frame.satellites.map { it.cn0DbHz.toDouble() }
        val meanE = elevations.average()
        val meanC = cn0s.average()
        var cov = 0.0
        var varE = 0.0
        var varC = 0.0
        elevations.indices.forEach { i ->
            val de = elevations[i] - meanE
            val dc = cn0s[i] - meanC
            cov += de * dc
            varE += de * de
            varC += dc * dc
        }
        val correlation = cov / sqrt(varE * varC)
        assertTrue("correlation=$correlation", correlation > 0.7)
    }
}
