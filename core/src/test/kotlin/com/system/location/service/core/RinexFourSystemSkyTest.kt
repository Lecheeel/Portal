package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Reproducible real-data integration, distinct from analytic single-orbit fixtures. */
class RinexFourSystemSkyTest {
    private val source = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system-source.json")).readText()).jsonObject
    private val text = requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system.rnx")).readText().replace("\r\n", "\n")
    private val epoch = Instant.parse(source.getValue("evaluationUtc").jsonPrimitive.content).toEpochMilli()
    private val resource = RinexNavigationResource.import(text, source.getValue("sourceUrl").jsonPrimitive.content, epoch + 60000)
    private val limits = GnssOutputLimits(128, 36, 32)
    private val systems = setOf(Constellation.GPS, Constellation.GALILEO, Constellation.BEIDOU, Constellation.GLONASS)

    @Test fun realFourSystemSkyChangesWithTargetAndKeepsNativeEpochProvenanceAndSignalCarriers() {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(source.getValue("subsetCanonicalLfSha256").jsonPrimitive.content, digest)
        val model = GnssBundledOrbitResources.model(GnssSimulationPolicy(), limits, 604800.0, navigation = resource)
        val frames = listOf(Wgs84(31.0, 121.0), Wgs84(-31.0, -59.0)).map {
            model.frame(GnssSkyRequest(epoch, it, 0.0))!!
        }
        frames.forEach { frame ->
            val broadcast = frame.orbitProvenance.filterValues { it.kind == GnssOrbitSourceKind.BROADCAST_EPHEMERIS }
            assertEquals(systems, broadcast.keys.map { it.first }.toSet())
            assertTrue(broadcast.values.all { it.referenceEpochMillis != resource.downloadedAtMillis })
            assertTrue(frame.orbitProvenance.values.none { it.kind == GnssOrbitSourceKind.VIRTUAL })
            assertTrue(frame.satellites.all { it.elevationDeg >= 0 && it.azimuthDeg < 360 })
            assertEquals(frame.satellites.size, frame.satellites.map { it.constellation to it.svid }.distinct().size)
            assertNotNull(frame.geometry.dop)
            assertTrue(frame.satellites.filter { it.constellation == Constellation.GLONASS }.all { it.carrierHz != null })
        }
        assertNotEquals(frames[0].satellites, frames[1].satellites)
        assertNotEquals(frames[0].geometry.dop, frames[1].geometry.dop)
    }

    @Test fun quantityGuaranteeAndExpiredEvidenceUseExplicitVirtualSourcesWithoutDuplicatingRealIdentities() {
        val policy = GnssSimulationPolicy(minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY)
        val model = GnssBundledOrbitResources.model(policy, limits, 604800.0, navigation = resource)
        val request = GnssSkyRequest(epoch, Wgs84(31.0, 121.0), 0.0)
        val frame = model.frame(request, 64)!!
        assertEquals(64, frame.satellites.size)
        assertTrue(frame.orbitProvenance.values.any { it.kind == GnssOrbitSourceKind.BROADCAST_EPHEMERIS })
        assertTrue(frame.orbitProvenance.values.any { it.kind == GnssOrbitSourceKind.VIRTUAL })
        assertEquals(64, frame.satellites.map { it.constellation to it.svid }.distinct().size)
        assertTrue(frame.satellites.count { it.usedInFix } <= 32)
        val expired = model.frame(request.copy(atMillis = Instant.parse("2028-01-01T00:00:00Z").toEpochMilli()), 32)!!
        assertTrue(expired.satellites.size >= 32)
        assertTrue(expired.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.VIRTUAL })
    }
}
