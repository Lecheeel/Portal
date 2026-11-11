package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.HookConfiguration
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GnssSourceSelectionTest {
    @Test fun acceptedNavigationReachesAutonomousGeometryWithoutMutatingOldPublications() {
        val at = java.time.Instant.parse("2026-11-07T23:59:42Z").toEpochMilli()
        val location = sample.copy(coordinate = Wgs84(0.0, 0.0), timeMillis = at)
        val configuration = HookConfiguration(gnssSimulation = GnssSimulationPolicy(virtualSeed = 904))
        val before = configuration.gnssFrameSource()!!
        val previous = before.frame(location, 0, GnssFrameRoute.Global, at)!!
        val resource = RinexNavigationResource.import(RinexNavigationResourceTest.navigation(),
            "https://example.org/nav", at)
        val updated = configuration.copy(gnssNavigation = resource)
        val source = updated.gnssFrameSource()!!
        val publication = source.frame(location, 0, GnssFrameRoute.Global, at)!!
        val provenance = publication.frame.orbitProvenance[Constellation.GPS to 1]!!
        assertEquals(GnssOrbitSourceKind.BROADCAST_EPHEMERIS, provenance.kind)
        assertEquals(resource.sha256, provenance.revision)
        assertTrue(publication.sequence > previous.sequence)
        assertSame(previous, before.frame(location, 0, GnssFrameRoute.Global, at))
        assertSame(source, updated.copy(gnssNavigation = resource.copy(downloadedAtMillis = at + 1000)).gnssFrameSource())
        assertFalse(Json.encodeToString(HookConfiguration.serializer(), updated).contains("navigation.rnx"))
        // Once the broadcast expires, the same source continues to select applicable fallbacks.
        assertNotNull(source.frame(location.copy(timeMillis = at + 86400000), 0, GnssFrameRoute.Global, at + 86400000))
        assertThrows(IllegalArgumentException::class.java) {
            updated.copy(gnssNavigation = resource.copy(navigation = resource.navigation + "x")).gnssFrameSource()
        }
    }

    private val point = Wgs84(31.0, 121.0)
    private val sample = LocationSample(point, 10.0, 5f, 2f, 90f, 1794369600000, 1_000_000_000)
    private fun capture() = CapturedGnssSource.prepare(GnssCaptureWire.encode(GnssCapture(
        deviceModel = "test", osVersion = "test", startedAtMillis = 1000, endedAtMillis = 61000,
        frames = (0..60).map { GnssObservationFrame(1000 + it * 1000L, point, satellites = listOf(
            GnssSatellite(Constellation.GPS, 1, 30f, 120f, 35f, false, true, true))) })))!!

    @Test fun autoDoesNotRequireCaptureAndExplicitReplayAndOffPreserveTheirMeaning() {
        val policy = GnssSimulationPolicy(virtualSeed = 901)
        val source = GnssSourceSelection.source(policy, null)!!
        assertNotNull(source.frame(sample, 0, GnssFrameRoute.Global, sample.timeMillis))
        val recording = capture()
        assertSame(source, GnssSourceSelection.source(policy, recording))
        assertSame(recording, GnssSourceSelection.source(policy.copy(mode = GnssSimulationMode.REPLAY), recording))
        assertNull(GnssSourceSelection.source(policy.copy(mode = GnssSimulationMode.REPLAY), null))
        assertNull(GnssSourceSelection.source(policy.copy(mode = GnssSimulationMode.OFF), recording))
    }

    @Test fun immutableConfigurationCopiesReuseSourceWithoutSerializingCaches() {
        val configuration = HookConfiguration(gnssSimulation = GnssSimulationPolicy(virtualSeed = 902))
        val source = configuration.gnssFrameSource()!!
        val first = source.frame(sample, 0, GnssFrameRoute.Global, sample.timeMillis)!!
        val changedFlag = configuration.copy(debugLog = true)
        assertSame(source, changedFlag.gnssFrameSource())
        assertSame(first, changedFlag.gnssFrameSource()!!.frame(sample, 0, GnssFrameRoute.Global, sample.timeMillis))
        val wire = Json.encodeToString(HookConfiguration.serializer(), configuration)
        assertFalse(wire.contains("selectedGnssSource"))
        assertSame(source, Json.decodeFromString(HookConfiguration.serializer(), wire).gnssFrameSource())
        assertNotSame(source, configuration.copy(gnssSimulation = configuration.gnssSimulation.copy(
            environment = GnssSimulationEnvironment.INDOOR)).gnssFrameSource())
    }

    @Test fun captureAndAutonomousPublicationsShareMonotonicSequenceAcrossSourceSwitches() {
        val capture = capture()
        val replaySample = sample.copy(timeMillis = 62000)
        val before = capture.frame(replaySample, 0, GnssFrameRoute.Global, 62000)!!
        val autonomous = GnssSourceSelection.source(GnssSimulationPolicy(virtualSeed = 903), null)!!
            .frame(sample, 0, GnssFrameRoute.Global, sample.timeMillis)!!
        assertTrue(autonomous.sequence > before.sequence)
        val after = capture.frame(replaySample.copy(timeMillis = 63000), 0, GnssFrameRoute.Global, 63000)!!
        assertTrue(after.sequence > autonomous.sequence)
    }
}
