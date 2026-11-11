package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GnssBundledOrbitResourcesTest {
    private val limits = GnssOutputLimits(128, 36, 32)
    private val current = Instant.parse("2026-11-11T04:00:00Z").toEpochMilli()
    private val future = Instant.parse("2028-01-01T00:00:00Z").toEpochMilli()
    private val request = GnssSkyRequest(current, Wgs84(31.0, 121.0), 10.0)

    @Test fun packagedResourcesCreateOfflineFramesWithoutCaptureAndRemainUsefulAfterRealDataExpires() {
        val model = GnssBundledOrbitResources.model(GnssSimulationPolicy(), limits, 604800.0)
        val frame = model.frame(request)!!
        assertTrue(frame.satellites.isNotEmpty())
        assertTrue(frame.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.ALMANAC })
        val expired = model.frame(request.copy(atMillis = future))!!
        assertTrue(expired.satellites.isNotEmpty())
        assertTrue(expired.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.VIRTUAL })
        assertTrue(expired.satellites.map { it.constellation }.distinct().size > 1)
    }

    @Test fun corruptOptionalDownloadedResourceRetainsCompleteBundledAlmanac() {
        val original = GpsAlmanacResource.decode(requireNotNull(javaClass.getResource("/gnss/gps-yuma.json")).readText())
        val model = GnssBundledOrbitResources.model(GnssSimulationPolicy(), limits, 604800.0,
            original.copy(sha256 = "0".repeat(64)))
        val frame = model.frame(request)!!
        assertTrue(frame.satellites.isNotEmpty())
        assertTrue(frame.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.ALMANAC })
        assertTrue(frame.diagnostics.any { it.contains("校验失败") })
    }

    @Test fun quantityScenarioCanReach128WithoutClaiming128UsedOrLiftingReservedOrbits() {
        val model = GnssBundledOrbitResources.model(GnssSimulationPolicy(
            minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY), limits, 604800.0)
        for (at in listOf(current, future)) for (point in listOf(Wgs84(0.0, 0.0), Wgs84(31.0, 121.0),
            Wgs84(-50.0, -60.0), Wgs84(89.0, 179.0))) {
            val frame = model.frame(request.copy(atMillis = at, observer = point), 128)!!
            assertEquals("$at $point: ${frame.diagnostics}", 128, frame.satellites.size)
            assertTrue(frame.satellites.count { it.usedInFix } <= 32)
            assertEquals(128, frame.orbitProvenance.size)
            assertTrue(frame.satellites.all { it.elevationDeg >= 0 && it.azimuthDeg < 360 })
        }
    }
}
