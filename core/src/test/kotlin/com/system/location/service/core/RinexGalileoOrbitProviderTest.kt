package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RinexGalileoOrbitProviderTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val epoch = Instant.parse("2026-11-07T23:59:42Z").toEpochMilli()
    private val record = RinexNavigationRecord(RinexNavigationSystem.GALILEO, 1,
        RinexNavigationEpoch(LocalDate.of(2026, 11, 8), 0, 0, 0), listOf(0.0, 0.0, 0.0),
        listOf(listOf(7.0, 0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, sqrt(29600000.0)),
            listOf(0.0, 0.0, 0.0, 0.0), listOf(0.95, 0.0, 0.0, 0.0),
            listOf(0.0, 517.0, 2444.0, null), listOf(3.12, 0.0, 0.0, 0.0),
            listOf(-30.0, null, null, null)))
    private val provenance = GnssOrbitProvenance("test-rinex-galileo", "1", "test", epoch,
        GnssOrbitSourceKind.BROADCAST_EPHEMERIS)
    private val request = GnssSkyRequest(epoch - 250, Wgs84(31.0, 121.0), 0.0)
    private fun header(offset: Double = .25) = String.format(Locale.ROOT,
        "GAGP %17.10E%16.9E %6d%5d", offset, 0.0, 0, 2444).padEnd(60)
    private fun change(row: Int, column: Int, value: Double?) = record.copy(orbit = record.orbit.mapIndexed { r, values ->
        if (r == row) values.mapIndexed { c, old -> if (c == column) value else old } else values
    })
    private fun provider(records: List<RinexNavigationRecord> = listOf(record), headers: List<String> = listOf(header()),
        policy: RinexGalileoSkyPolicy = RinexGalileoSkyPolicy(86400.0, true)) =
        RinexGalileoOrbitProvider(RinexNavigationFile("3.05", headers, emptyList(), records), provenance, utc, policy)

    @Test fun downloadedResourceReachesBundledProductionModelAndExpiresIntoIndependentFallback() {
        fun fields(values: List<Double?>) = values.joinToString("") { value ->
            value?.let { String.format(Locale.ROOT, "%19.12E", it) } ?: " ".repeat(19)
        }
        val text = "     3.05           NAVIGATION DATA     M".padEnd(60) + "RINEX VERSION / TYPE\n" +
            header() + "TIME SYSTEM CORR\n" + "".padEnd(60) + "END OF HEADER\n" +
            "E01 2026 11 08 00 00 00" + fields(record.clock) + "\n" +
            record.orbit.joinToString("\n") { "    " + fields(it) } + "\n"
        val resource = RinexNavigationResource.import(text, "https://example.org/navigation.rnx", epoch + 60000)
        val position = resource.galileoProvider(utc, RinexGalileoSkyPolicy(86400.0, true)).positions(request).positions.single()
        assertEquals(epoch - 250, position.provenance.referenceEpochMillis)
        assertNotEquals(resource.downloadedAtMillis, position.provenance.referenceEpochMillis)
        val model = GnssBundledOrbitResources.model(GnssSimulationPolicy(), GnssOutputLimits(128, 36, 32),
            604800.0, navigation = resource)
        val frame = model.frame(request.copy(observer = Wgs84(0.0, 0.0)))!!
        assertEquals(GnssOrbitSourceKind.BROADCAST_EPHEMERIS,
            frame.orbitProvenance[Constellation.GALILEO to 1]?.kind)
        val expired = model.frame(request.copy(atMillis = Instant.parse("2028-01-01T00:00:00Z").toEpochMilli()))!!
        assertTrue(expired.satellites.isNotEmpty())
        assertTrue(expired.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.VIRTUAL })
    }

    @Test fun nonzeroGagpAndGstOrbitUseNativeEpochAndIndependentCircularReference() {
        val result = provider().positions(request)
        val position = result.positions.single()
        assertEquals(29600000.0, position.position.xMeters, 1e-7)
        assertEquals(0.0, position.position.yMeters, 1e-7)
        assertEquals(0.0, position.position.zMeters, 1e-7)
        assertEquals(epoch - 250, position.provenance.referenceEpochMillis)
        assertTrue(position.hasEphemeris)
        assertFalse(position.hasAlmanac)
        assertTrue(result.diagnostics.any { "approximate GTRF" in it })
        assertEquals(result.positions, provider().positions(request.copy(observer = Wgs84(-31.0, -59.0))).positions)
        assertTrue(provider().positions(request.copy(atMillis = epoch + 14400001)).positions.isEmpty())
    }

    @Test fun missingConflictingStaleCorrectionsOrUnpermittedFrameCannotPretendGpsEqualsGst() {
        for (source in listOf(provider(headers = emptyList()), provider(headers = listOf(header(), header(.5))),
            provider(policy = RinexGalileoSkyPolicy(86400.0, false)))) {
            val rejected = source.positions(request)
            assertTrue(rejected.positions.isEmpty())
            assertEquals(setOf(Constellation.GALILEO to 1), rejected.reservedIdentities)
            assertTrue(rejected.diagnostics.isNotEmpty())
        }
        assertTrue(provider().positions(request.copy(atMillis = epoch + 86400001)).positions.isEmpty())
        assertTrue(provider().positions(request.copy(atMillis = utc.expiresAtMillis)).positions.isEmpty())
        assertEquals(1, provider(headers = listOf(header(), header())).positions(request).positions.size)
    }

    @Test fun e1HealthAndLatestUploadCannotBeReplacedByFnavOrExpiredHealthyHistory() {
        val unhealthy = change(5, 1, 1.0).let { r -> r.copy(orbit = r.orbit.mapIndexed { row, values ->
            if (row == 6) listOf(60.0, null, null, null) else values
        }) }
        val source = provider(listOf(record, unhealthy))
        assertEquals(1, source.positions(request).positions.size)
        val after = request.copy(atMillis = epoch + 60000)
        assertEquals(setOf(Constellation.GALILEO to 1), source.positions(after).blockedIdentities)
        assertTrue(source.positions(after).positions.isEmpty())
        assertTrue(source.positions(request.copy(atMillis = epoch + 14400001)).blockedIdentities.isEmpty())
        assertTrue(provider(listOf(change(4, 1, 258.0))).positions(request).positions.isEmpty())
        assertTrue(provider(listOf(change(5, 0, -1.0))).positions(request).positions.isEmpty())
        // Same-transmission conflicting health fails closed, including lower-priority sources.
        val conflict = provider(listOf(record, change(5, 1, 1.0))).positions(request)
        assertTrue(conflict.positions.isEmpty())
        assertEquals(setOf(Constellation.GALILEO to 1), conflict.blockedIdentities)
        assertTrue(provider(listOf(change(6, 0, 60.0))).positions(request).positions.isEmpty())
    }
}
