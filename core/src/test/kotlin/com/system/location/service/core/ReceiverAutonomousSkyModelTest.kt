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

    @Test fun physicalFloorReportsShortageWhileQuantityPolicyUsesOnlyUnreservedVirtualIds() {
        val identities = (1..32).map { Constellation.GPS to it } + (1..36).map { Constellation.GALILEO to it }
        val provider = almanac.provider(utc, 604800.0)
        val physical = ReceiverAutonomousSkyModel(listOf(provider), virtual, GnssSimulationPolicy()).frame(request, 32)!!
        assertTrue(physical.satellites.size < 32)
        assertTrue(physical.diagnostics.any { it.contains("未满足") })
        val model = ReceiverAutonomousSkyModel(listOf(provider), virtual,
            GnssSimulationPolicy(minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY),
            quantitySupplement = GnssVirtualSkySupplement(identities, GnssOutputLimits(64, 36, 16), 123))
        val guaranteed = model.frame(request, 32)!!
        assertEquals(32, guaranteed.satellites.size)
        assertEquals(32, guaranteed.requestedMinimumSatellites)
        assertTrue(guaranteed.satellites.count { it.usedInFix } <= 16)
        assertEquals(physical.satellites.map { it.constellation to it.svid }.toSet(),
            guaranteed.orbitProvenance.filterValues { it.kind != GnssOrbitSourceKind.VIRTUAL }.keys)
        // All GPS identities, including the hidden ones, are reserved by the real upload.
        assertTrue(guaranteed.orbitProvenance.filterValues { it.kind == GnssOrbitSourceKind.VIRTUAL }
            .keys.all { it.first == Constellation.GALILEO })
        assertTrue(guaranteed.availableSatelliteCapacity >= 32)
        val unsupported = model.frame(request, 64)!!
        assertTrue(unsupported.satellites.size < 64)
        assertTrue(unsupported.diagnostics.any { it.contains("无法保证") })
    }

    @Test fun guaranteedIndoorVisibleFloorStillDoesNotPromiseTrackingOrFiniteDop() {
        val identities = (1..36).map { Constellation.GALILEO to it }
        val model = ReceiverAutonomousSkyModel(listOf(almanac.provider(utc, 604800.0)), virtual,
            GnssSimulationPolicy(minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY,
                environment = GnssSimulationEnvironment.INDOOR),
            quantitySupplement = GnssVirtualSkySupplement(identities, GnssOutputLimits(64, 36, 32), 123))
        val frame = model.frame(request, 32)!!
        assertEquals(32, frame.satellites.size)
        assertEquals(0, frame.trackedSatelliteCount)
        assertTrue(frame.satellites.none { it.usedInFix })
        assertNull(frame.geometry.dop)
    }
}
