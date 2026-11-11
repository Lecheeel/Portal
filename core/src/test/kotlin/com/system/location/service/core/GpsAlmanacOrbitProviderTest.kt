package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class GpsAlmanacOrbitProviderTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(
        javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val epoch = Instant.parse("2026-11-08T00:00:00Z").toEpochMilli()
    private val reference = utc.gpsAtUtcMillis(epoch).time!!
    private val record = GpsYumaAlmanac(1, 0, reference, 0.01, 0.95, -8e-9,
        sqrt(26_560_000.0), 1.0, 0.5, 2.0, 0.0, 0.0)
    private val provenance = GnssOrbitProvenance("synthetic-yuma", "1", "test", epoch, GnssOrbitSourceKind.ALMANAC)
    private val request = GnssSkyRequest(epoch, Wgs84(31.0, 121.0), 0.0)

    @Test fun worksWithoutCaptureAndDoesNotClaimEphemerisOrMeasuredSignal() {
        val provider = GpsAlmanacOrbitProvider(listOf(record), provenance, utc, 604800.0)
        val first = provider.positions(request).positions.single()
        assertEquals(provenance, first.provenance)
        assertFalse(first.hasEphemeris)
        assertTrue(first.hasAlmanac)
        val later = provider.positions(request.copy(atMillis = epoch + 1000)).positions.single()
        assertNotEquals(first.position, later.position)
        // A satellite's global ECEF position is shareable; target ENU projection happens later.
        assertEquals(first, provider.positions(request.copy(observer = Wgs84(-31.0, -59.0))).positions.single())
    }

    @Test fun invalidIdentityDoesNotSuppressHealthyRemainderAndAgeIsIndependentOfCapture24Hours() {
        val provider = GpsAlmanacOrbitProvider(listOf(record, record.copy(svid = 2, health = 63)),
            provenance, utc, 604800.0)
        val oldCaptureAge = provider.positions(request.copy(atMillis = epoch + 2 * 86400000L))
        assertEquals(listOf(1), oldCaptureAge.positions.map { it.svid })
        assertTrue(oldCaptureAge.diagnostics.single().contains("GPS:2"))
        val expired = provider.positions(request.copy(atMillis = epoch + 8 * 86400000L))
        assertTrue(expired.positions.isEmpty())
        assertEquals(2, expired.diagnostics.size)
        assertTrue(provider.positions(request.copy(atMillis = utc.expiresAtMillis)).positions.isEmpty())
    }

    @Test fun rejectsWrongEraMetadataAndCopiesUploadBeforePublication() {
        assertThrows(IllegalArgumentException::class.java) {
            GpsAlmanacOrbitProvider(listOf(record), provenance.copy(referenceEpochMillis = epoch + 86400000), utc, 604800.0)
        }
        val mutable = mutableListOf(record)
        val provider = GpsAlmanacOrbitProvider(mutable, provenance, utc, 604800.0)
        mutable.clear()
        assertEquals(1, provider.positions(request).positions.size)
    }
}
