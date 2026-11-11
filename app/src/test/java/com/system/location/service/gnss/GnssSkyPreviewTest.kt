package com.system.location.service.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import org.junit.Assert.*
import org.junit.Test

class GnssSkyPreviewTest {
    @Test fun previewMatchesAutoSignalCalibrationAndUsesRealValidationTimeForExpiry() {
        val at = sample.timeMillis
        val capture = CapturedGnssSource.prepare(GnssCaptureWire.encode(GnssCapture(
            deviceModel = "test", osVersion = "test", startedAtMillis = at, endedAtMillis = at,
            frames = listOf(GnssObservationFrame(at, Wgs84(0.0, 0.0), satellites = listOf(
                GnssSatellite(Constellation.GALILEO, 1, 0f, 0f, 25f, false, false, true, 1_575_420_000f),
                GnssSatellite(Constellation.GALILEO, 2, 90f, 0f, 25f, false, false, true, 1_575_420_000f)))))))!!
        val policy = GnssSimulationPolicy(virtualSeed = 1234568)
        val preview = GnssSkyPreview()
        val predicted = preview.frame(sample, 0, policy, null, capture, at)!!
        val production = GnssSourceSelection.source(policy, capture)!!.frame(sample, 0,
            GnssFrameRoute.Global, at)!!.frame
        assertEquals(production, predicted)
        val expired = preview.frame(sample, 0, policy, null, capture,
            at + GnssCaptureValidator.MAX_AGE_MILLIS + 1)!!
        val uncalibrated = GnssSkyPreview().frame(sample, 0, policy, null)!!
        assertEquals(uncalibrated.satellites, expired.satellites)
        assertNotEquals(predicted.satellites.map { it.cn0DbHz }, expired.satellites.map { it.cn0DbHz })
    }

    private val sample = LocationSample(Wgs84(31.0, 121.0), 20.0, 5f, 0f, 0f, 1830297600000, 1_000_000_000)

    @Test fun previewOfOfflineSkyReflectsTargetEnvironmentAndQuantityWithoutCapture() {
        val preview = GnssSkyPreview()
        val policy = GnssSimulationPolicy(minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY)
        val frame = preview.frame(sample, 32, policy, null)!!
        assertEquals(sample.coordinate, frame.observer)
        assertEquals(32, frame.satellites.size)
        val indoor = preview.frame(sample, 32, policy.copy(environment = GnssSimulationEnvironment.INDOOR), null)!!
        assertEquals(32, indoor.satellites.size)
        assertEquals(0, indoor.trackedSatelliteCount)
        assertNull(indoor.geometry.dop)
        val remote = preview.frame(sample.copy(coordinate = Wgs84(-31.0, -59.0)), 32, policy, null)!!
        assertNotEquals(frame.satellites, remote.satellites)
        assertNull(preview.frame(sample, 32, policy.copy(mode = GnssSimulationMode.OFF), null))
        assertNull(preview.frame(sample, 32, policy.copy(mode = GnssSimulationMode.REPLAY), null))
    }

    @Test fun previewCannotAnchorProductionPublicationToTheUiSample() {
        val policy = GnssSimulationPolicy(virtualSeed = 1234567)
        val source = GnssSourceSelection.source(policy, null)!!
        val previewPoint = sample.copy(coordinate = Wgs84(0.0, 0.0))
        val prediction = GnssSkyPreview().frame(previewPoint, 0, policy, null)!!
        val publication = source.frame(sample, 0, GnssFrameRoute.Scenario("preview-isolation"), sample.timeMillis)!!
        assertEquals(sample.coordinate, publication.frame.observer)
        assertNotEquals(prediction.observer, publication.frame.observer)
        assertEquals(GnssSkyPreview().frame(sample, 0, policy, null), publication.frame)
    }
}
