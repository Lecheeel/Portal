package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GpsAlmanacResourceTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(
        javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val resource = GpsAlmanacResource.decode(requireNotNull(
        javaClass.getResource("/gnss/gps-yuma.json")).readText())

    @Test fun bundledPublicResourceHasVerifiedEpochDigestAndHealthyOrbitPositions() {
        assertEquals(resource, GpsAlmanacResource.decode(GpsAlmanacResource.encode(resource)))
        val request = GnssSkyRequest(resource.referenceEpochMillis, Wgs84(31.0, 121.0), 10.0)
        val provider = resource.provider(utc, 604800.0)
        val batch = provider.positions(request)
        val parsed = GpsYumaParser.parse(resource.yuma, resource.resolvedReferenceWeek)
        assertEquals(parsed.count { it.health == 0 }, batch.positions.size)
        assertTrue(batch.positions.size > 20)
        for (position in batch.positions) {
            val p = position.position
            val radius = kotlin.math.sqrt(p.xMeters * p.xMeters + p.yMeters * p.yMeters + p.zMeters * p.zMeters)
            assertTrue(radius in 25_000_000.0..28_000_000.0)
            assertEquals(resource.sha256, position.provenance.revision)
        }
    }

    @Test fun redownloadDoesNotRefreshOrbitalReferenceOrExtendApplicability() {
        val again = GpsAlmanacResource.importYuma(resource.yuma, resource.sourceUrl,
            resource.downloadedAtMillis, resource.downloadedAtMillis + 86400000, utc)
        assertEquals(resource.referenceEpochMillis, again.referenceEpochMillis)
        assertEquals(resource.resolvedReferenceWeek, again.resolvedReferenceWeek)
        assertEquals(resource.sha256, again.sha256)
        val old = GnssSkyRequest(resource.referenceEpochMillis + 8 * 86400000L, Wgs84(0.0, 0.0), 0.0)
        assertTrue(again.provider(utc, 604800.0).positions(old).positions.isEmpty())
    }

    @Test fun corruptDigestAndMetadataRejectBeforePublishingProvider() {
        for (broken in listOf(resource.copy(sha256 = "0".repeat(64)),
            resource.copy(referenceEpochMillis = resource.referenceEpochMillis + 1000),
            resource.copy(resolvedReferenceWeek = resource.resolvedReferenceWeek + 1024))) {
            assertThrows(IllegalArgumentException::class.java) { broken.provider(utc, 604800.0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsAlmanacResource.importYuma(resource.yuma.substringBefore("Af0(s)"),
                resource.sourceUrl, resource.downloadedAtMillis, resource.downloadedAtMillis, utc)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsAlmanacResource.decode(GpsAlmanacResource.encode(resource).replace("\"formatVersion\":1", "\"formatVersion\":2"))
        }
    }
}
