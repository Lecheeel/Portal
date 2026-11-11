package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverAutonomousSkyModelTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(
        javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val almanac = GpsAlmanacResource.decode(requireNotNull(
        javaClass.getResource("/gnss/gps-yuma.json")).readText())
    private val request = GnssSkyRequest(almanac.referenceEpochMillis, Wgs84(31.0, 121.0), 10.0)
    private val virtual = VirtualWalkerOrbitProvider(VirtualWalkerResource(revision = "test",
        referenceEpochMillis = 0, groups = listOf(
            VirtualWalkerGroup(Constellation.GPS, (1..32).toList(), 8, 1, 26_560_000.0, 0.95),
            VirtualWalkerGroup(Constellation.GALILEO, (1..36).toList(), 3, 1, 29_600_000.0, 0.98))), 123)

    @Test fun noCaptureNetworkOrResourceStillGeneratesTargetSkyAfterManyDays() {
        val model = ReceiverAutonomousSkyModel(emptyList(), virtual, GnssSimulationPolicy())
        val first = model.frame(request)!!
        val later = model.frame(request.copy(atMillis = request.atMillis + 10 * 86400000L))!!
        assertTrue(first.satellites.isNotEmpty() && later.satellites.isNotEmpty())
        assertTrue(first.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.VIRTUAL })
        assertEquals(first, model.frame(request))
        assertEquals(first.satellites.size, first.orbitProvenance.size)
        assertNotEquals(first.satellites, later.satellites)
    }

    @Test fun healthyAlmanacIsUsedAndExpirationSelectsVirtualWholeFrame() {
        val model = ReceiverAutonomousSkyModel(listOf(almanac.provider(utc, 604800.0)), virtual, GnssSimulationPolicy())
        val physical = model.frame(request)!!
        assertTrue(physical.satellites.isNotEmpty())
        assertTrue(physical.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.ALMANAC })
        val fallback = model.frame(request.copy(atMillis = request.atMillis + 8 * 86400000L))!!
        assertTrue(fallback.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.VIRTUAL })
        assertTrue(fallback.diagnostics.any { it.contains("age budget") })
    }

    @Test fun targetLocationChangesGeometryRatherThanOnlyFrameLabel() {
        val model = ReceiverAutonomousSkyModel(emptyList(), virtual, GnssSimulationPolicy())
        val local = model.frame(request)!!
        val remote = model.frame(request.copy(observer = Wgs84(-31.0, -59.0)))!!
        assertNotEquals(local.satellites.map { it.constellation to it.svid }, remote.satellites.map { it.constellation to it.svid })
        assertTrue(local.satellites.all { it.elevationDeg >= 0 && it.azimuthDeg < 360 })
    }

    @Test fun indoorEnvironmentCanHaveVisibleSkyWithoutTrackingOrFix() {
        val model = ReceiverAutonomousSkyModel(emptyList(), virtual,
            GnssSimulationPolicy(environment = GnssSimulationEnvironment.INDOOR))
        val frame = model.frame(request)!!
        assertTrue(frame.satellites.isNotEmpty())
        assertEquals(0, frame.trackedSatelliteCount)
        assertTrue(frame.satellites.none { it.usedInFix })
        assertNull(frame.geometry.dop)
        assertTrue(frame.simulatedNavigationSatelliteCount > 0)
    }

    @Test fun noNavigationDoesNotGainUsedFlagsFromCountAndReplayOffDoNotRunAutonomousEngine() {
        val frame = ReceiverAutonomousSkyModel(emptyList(), virtual, GnssSimulationPolicy(),
            simulateNavigation = false).frame(request)!!
        assertTrue(frame.satellites.isNotEmpty())
        assertTrue(frame.satellites.none { it.usedInFix || it.hasEphemeris })
        assertNull(frame.geometry.dop)
        for (mode in listOf(GnssSimulationMode.OFF, GnssSimulationMode.REPLAY)) {
            assertNull(ReceiverAutonomousSkyModel(emptyList(), virtual, GnssSimulationPolicy(mode = mode)).frame(request))
        }
    }
}
