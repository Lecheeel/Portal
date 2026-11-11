package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RinexBeiDouOrbitProviderTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val catalog = BeiDouSkyCatalog(BeiDouSatelliteMetadata.parse(
        requireNotNull(javaClass.getResource("/gnss/igs-beidou-metadata.snx")).readText()), 2026, 30 * 86400L, utc)
    private val epoch = Instant.parse("2026-11-07T23:59:55.750Z").toEpochMilli()
    private val record = RinexNavigationRecord(RinexNavigationSystem.BEIDOU, 19,
        RinexNavigationEpoch(LocalDate.of(2026, 11, 8), 0, 0, 0), listOf(0.0, 0.0, 0.0),
        listOf(listOf(7.0, 0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, sqrt(27800000.0)),
            listOf(0.0, 0.0, 0.0, 0.0), listOf(0.95, 0.0, 0.0, 0.0),
            listOf(0.0, null, 1088.0, null), listOf(2.4, 0.0, 0.0, 0.0), listOf(-30.0, 7.0, null, null)))
    private val provenance = GnssOrbitProvenance("test-beidou", "1", "test", epoch, GnssOrbitSourceKind.BROADCAST_EPHEMERIS)
    private val policy = RinexBeiDouSkyPolicy(3600.0, 604800.0, true)
    private val request = GnssSkyRequest(epoch, Wgs84(31.0, 121.0), 0.0)
    private fun header(offset: Double = .25) = String.format(Locale.ROOT,
        "BDUT %17.10E%16.9E %6d%5d", offset, 0.0, 0, 1088).padEnd(60)
    private fun provider(records: List<RinexNavigationRecord> = listOf(record), headers: List<String> = listOf(header()),
        policy: RinexBeiDouSkyPolicy = this.policy) = RinexBeiDouOrbitProvider(
        RinexNavigationFile("3.05", headers, emptyList(), records), provenance, utc, catalog, policy)
    private fun change(row: Int, column: Int, value: Double?) = record.copy(orbit = record.orbit.mapIndexed { r, values ->
        if (r == row) values.mapIndexed { c, old -> if (c == column) value else old } else values
    })

    @Test fun fractionalBdtHealthAndNativeOrbitReachTheTargetEpochWithoutCaptureGeometry() {
        val position = provider().positions(request).positions.single()
        assertEquals(27800000.0, position.position.xMeters, 1e-7)
        assertEquals(0.0, position.position.yMeters, 1e-7)
        assertEquals(0.0, position.position.zMeters, 1e-7)
        assertEquals(epoch, position.provenance.referenceEpochMillis)
        assertEquals(1_561_098_000f, position.carrierHz)
        assertTrue(position.hasEphemeris)
        assertFalse(position.hasAlmanac)
        assertEquals(position, provider().positions(request.copy(observer = Wgs84(-31.0, -59.0))).positions.single())
        for (at in listOf(epoch + 3600001, epoch + 604800000, utc.expiresAtMillis))
            assertTrue(provider().positions(request.copy(atMillis = at)).positions.isEmpty())
    }

    @Test fun lowPrnGeoComesFromDatedSvnMetadataRatherThanHistoricalNumberRanges() {
        val geo = record.copy(svid = 4, orbit = record.orbit.mapIndexed { row, values -> when (row) {
            1 -> listOf(0.0, 0.0, 0.0, sqrt(42164000.0))
            3 -> listOf(0.0, 0.0, 0.0, 0.0)
            else -> values
        } })
        val position = provider(listOf(geo)).positions(request.copy(atMillis = epoch + 600000)).positions.single().position
        val decoded = RinexBeiDouImporter.decode(geo, BeiDouOrbitType.GEO, BeiDouTime(1087, 601200.0), BeiDouTime(1088, 3600.0))
        val expected = BeiDouBroadcastPropagator.propagate(decoded.ephemeris, BeiDouTime(1088, 600.0)).state!!
        assertEquals(EcefPosition(expected.xMeters, expected.yMeters, expected.zMeters), position)
        val wrong = BeiDouBroadcastPropagator.propagate(decoded.ephemeris.copy(orbitType = BeiDouOrbitType.MEO), BeiDouTime(1088, 600.0)).state!!
        assertTrue(kotlin.math.abs(position.zMeters - wrong.zMeters) > 1000)
        assertTrue(provider(listOf(record.copy(svid = 59))).positions(request).positions.isEmpty())
    }

    @Test fun latestMalformedConflictingUnhealthyOrFutureUploadNeverRevivesOldBroadcast() {
        val unhealthy = change(5, 1, 1.0).copy(orbit = change(5, 1, 1.0).orbit.mapIndexed { row, values ->
            if (row == 6) listOf(60.0, 7.0, null, null) else values
        })
        val after = request.copy(atMillis = epoch + 60000)
        assertEquals(1, provider(listOf(record, unhealthy)).positions(request).positions.size)
        assertEquals(setOf(Constellation.BEIDOU to 19), provider(listOf(record, unhealthy)).positions(after).blockedIdentities)
        val conflict = provider(listOf(record, change(5, 1, 1.0))).positions(request)
        assertTrue(conflict.positions.isEmpty())
        assertEquals(setOf(Constellation.BEIDOU to 19), conflict.blockedIdentities)
        val invalid = unhealthy.copy(orbit = unhealthy.orbit.mapIndexed { row, values ->
            if (row == 1) listOf(0.0, 0.0, 0.0, null) else values
        })
        assertTrue(provider(listOf(record, invalid)).positions(after).positions.isEmpty())
        assertTrue(provider(listOf(record, change(6, 0, null))).positions(request).positions.isEmpty())
        assertTrue(provider(listOf(unhealthy)).positions(request).positions.isEmpty())
    }

    @Test fun missingDamagedConflictingTimeCorrectionOrUnpermittedFrameUsesFallback() {
        for (source in listOf(provider(headers = emptyList()), provider(headers = listOf(header(), header(.5))),
            provider(headers = listOf(header(), "BDUT damaged")),
            provider(policy = policy.copy(allowAlignedFrameApproximation = false)))) {
            val batch = source.positions(request)
            assertTrue(batch.positions.isEmpty())
            assertEquals(setOf(Constellation.BEIDOU to 19), batch.reservedIdentities)
            assertTrue(batch.diagnostics.isNotEmpty())
        }
    }

    @Test fun downloadedBeiDouReachesProductionSkyWithReferenceTimeIndependentOfDownloadTime() {
        fun fields(values: List<Double?>) = values.joinToString("") { value ->
            value?.let { String.format(Locale.ROOT, "%19.12E", it) } ?: " ".repeat(19)
        }
        val text = "     3.05           NAVIGATION DATA     M".padEnd(60) + "RINEX VERSION / TYPE\n" +
            header() + "TIME SYSTEM CORR\n" + "".padEnd(60) + "END OF HEADER\n" +
            "C19 2026 11 08 00 00 00" + fields(record.clock) + "\n" +
            record.orbit.joinToString("\n") { "    " + fields(it) } + "\n"
        val resource = RinexNavigationResource.import(text, "https://example.org/navigation.rnx", epoch + 60000)
        val position = resource.beiDouProvider(utc, catalog, policy).positions(request).positions.single()
        assertEquals(epoch, position.provenance.referenceEpochMillis)
        val model = GnssBundledOrbitResources.model(GnssSimulationPolicy(), GnssOutputLimits(128, 36, 32),
            604800.0, navigation = resource)
        val frame = model.frame(request.copy(observer = Wgs84(0.0, 0.0)))!!
        assertEquals(GnssOrbitSourceKind.BROADCAST_EPHEMERIS, frame.orbitProvenance[Constellation.BEIDOU to 19]?.kind)
        assertEquals(epoch, frame.orbitProvenance[Constellation.BEIDOU to 19]?.referenceEpochMillis)
        assertTrue(frame.diagnostics.any { "IGS" in it || "files.igs.org" in it })
        val future = model.frame(request.copy(atMillis = Instant.parse("2028-01-01T00:00:00Z").toEpochMilli()))!!
        assertTrue(future.satellites.isNotEmpty())
        assertTrue(future.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.VIRTUAL })
    }
}
