package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import java.time.LocalDate
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RinexGpsOrbitProviderTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val epoch = Instant.parse("2026-11-07T23:59:42Z").toEpochMilli()
    // Native GPS midnight is 18 seconds ahead of UTC at this epoch.
    private val record = RinexNavigationRecord(RinexNavigationSystem.GPS, 1,
        RinexNavigationEpoch(LocalDate.of(2026, 11, 8), 0, 0, 0), listOf(0.0, 0.0, 0.0),
        listOf(listOf(7.0, 0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, sqrt(26560000.0)),
            listOf(0.0, 0.0, 0.0, 0.0), listOf(0.95, 0.0, 0.0, 0.0),
            listOf(0.0, 0.0, 2444.0, 0.0), listOf(2.4, 0.0, 0.0, 7.0),
            listOf(-30.0, 4.0, null, null)))
    private val provenance = GnssOrbitProvenance("synthetic-rinex", "1", "test", epoch,
        GnssOrbitSourceKind.BROADCAST_EPHEMERIS)
    private val request = GnssSkyRequest(epoch, Wgs84(31.0, 121.0), 0.0)
    private fun file(vararg records: RinexNavigationRecord) = RinexNavigationFile("3.04", emptyList(), emptyList(), records.toList())
    private fun change(row: Int, column: Int, value: Double?): RinexNavigationRecord =
        record.copy(orbit = record.orbit.mapIndexed { r, values ->
            if (r == row) values.mapIndexed { c, old -> if (c == column) value else old } else values
        })

    @Test fun nativeGpsEpochAndFitAreNotUtcOrModuloWeek() {
        val imported = RinexGpsImporter.decode(record)
        assertEquals(GpsTime(2444, 0.0), imported.ephemeris.toe)
        assertEquals(imported.ephemeris.toe, imported.ephemeris.toc)
        assertEquals(GpsTime(2443, 604770.0), imported.availableFrom)
        assertEquals(-7200.0, imported.ephemeris.validFrom.secondsSince(imported.ephemeris.toe), 0.0)
        assertEquals(7200.0, imported.ephemeris.validUntil.secondsSince(imported.ephemeris.toe), 0.0)
        val provider = RinexGpsOrbitProvider(file(record), provenance, utc)
        val position = provider.positions(request).positions.single()
        // Analytic circular orbit at zero anomaly/node: independent of importer/propagator logic.
        assertEquals(26560000.0, position.position.xMeters, 1e-7)
        assertEquals(0.0, position.position.yMeters, 1e-7)
        assertEquals(0.0, position.position.zMeters, 1e-7)
        assertTrue(position.hasEphemeris)
        assertFalse(position.hasAlmanac)
        assertTrue(provider.positions(request.copy(atMillis = epoch + 604800000)).positions.isEmpty())
        assertTrue(provider.positions(request.copy(atMillis = utc.expiresAtMillis)).positions.isEmpty())
        // Global orbital position does not depend on a capture point or local receiver.
        assertEquals(position, provider.positions(request.copy(observer = Wgs84(-31.0, -59.0))).positions.single())
    }

    @Test fun latestApplicableNegativeHealthBlocksOlderHealthyUploadsButNotForever() {
        val unhealthy = change(5, 1, 63.0).let { old -> old.copy(orbit = old.orbit.mapIndexed { row, values ->
            if (row == 6) listOf(60.0, 4.0, null, null) else values
        }) }
        val provider = RinexGpsOrbitProvider(file(record, unhealthy), provenance, utc)
        assertEquals(1, provider.positions(request).positions.size)
        val after = provider.positions(request.copy(atMillis = epoch + 60000))
        assertTrue(after.positions.isEmpty())
        assertEquals(setOf(Constellation.GPS to 1), after.blockedIdentities)
        assertEquals(after.blockedIdentities, after.reservedIdentities)
        val expired = provider.positions(request.copy(atMillis = epoch + 7200001))
        assertTrue(expired.positions.isEmpty())
        assertTrue(expired.blockedIdentities.isEmpty())
    }

    @Test fun missingFitOrTransmissionAndWrongEraCannotBeSilentlyInvented() {
        for (bad in listOf(change(6, 1, null), change(6, 1, 0.0), change(6, 0, 999999999.999),
            change(4, 2, 1420.0), change(0, 0, 7.5), change(5, 3, 8.0), change(2, 0, 604800.0),
            record.copy(epoch = record.epoch.copy(second = 60)))) {
            assertThrows(IllegalArgumentException::class.java) { RinexGpsImporter.decode(bad) }
            val rejected = RinexGpsOrbitProvider(file(bad), provenance, utc).positions(request)
            assertTrue(rejected.positions.isEmpty())
            assertEquals(setOf(Constellation.GPS to 1), rejected.reservedIdentities)
            assertTrue(rejected.diagnostics.isNotEmpty())
        }
        val futureUpload = RinexGpsOrbitProvider(file(change(6, 0, 60.0)), provenance, utc)
        assertTrue(futureUpload.positions(request).positions.isEmpty())
        assertEquals(1, futureUpload.positions(request.copy(atMillis = epoch + 60000)).positions.size)
    }

    @Test fun supersededLongFitAndConflictingSameTransmissionCannotReviveHealthyData() {
        val longFit = change(6, 1, 26.0)
        val newer = change(5, 1, 63.0).copy(orbit = change(5, 1, 63.0).orbit.mapIndexed { row, values ->
            if (row == 6) listOf(60.0, 4.0, null, null) else values
        })
        val provider = RinexGpsOrbitProvider(file(longFit, newer), provenance, utc)
        val expiredNewer = provider.positions(request.copy(atMillis = epoch + 3 * 3600000))
        assertTrue(expiredNewer.positions.isEmpty())
        assertTrue(expiredNewer.blockedIdentities.isEmpty())
        val conflict = RinexGpsOrbitProvider(file(record, change(5, 1, 63.0)), provenance, utc).positions(request)
        assertTrue(conflict.positions.isEmpty())
        assertTrue(conflict.diagnostics.isNotEmpty())
        assertEquals(setOf(Constellation.GPS to 1), conflict.blockedIdentities)
        assertEquals(1, RinexGpsOrbitProvider(file(record, record), provenance, utc).positions(request).positions.size)
    }

    @Test fun rejectingNewerMalformedPayloadNeverRestoresAnOlderHealthyBroadcast() {
        val invalid = change(6, 1, null).let { raw -> raw.copy(orbit = raw.orbit.mapIndexed { row, values ->
            if (row == 6) listOf(60.0, null, null, null) else values
        }) }
        val source = RinexGpsOrbitProvider(file(record, invalid), provenance, utc)
        assertEquals(1, source.positions(request).positions.size)
        val after = source.positions(request.copy(atMillis = epoch + 60000))
        assertTrue(after.positions.isEmpty())
        assertEquals(setOf(Constellation.GPS to 1), after.reservedIdentities)
        val unknownOrder = RinexGpsOrbitProvider(file(record, change(6, 0, null)), provenance, utc).positions(request)
        assertTrue(unknownOrder.positions.isEmpty())
        assertTrue(unknownOrder.diagnostics.any { "ordering unavailable" in it })
    }

    @Test fun angularAndHarmonicParametersKeepTheirSpecifiedUnitsAndColumns() {
        val raw = record.copy(clock = listOf(1e-4, 2e-12, 3e-20), orbit = listOf(
            listOf(7.0, 12.0, 3e-9, 0.4), listOf(5e-6, 0.01, 6e-6, 5153.7),
            listOf(0.0, 7e-8, 0.8, 9e-8), listOf(0.95, 13.0, 1.1, -8e-9),
            listOf(2e-10, 0.0, 2444.0, 0.0), listOf(2.4, 0.0, -4e-9, 7.0), listOf(-30.0, 4.0, null, null)))
        val e = RinexGpsImporter.decode(raw).ephemeris
        assertEquals(listOf(0.95, 0.8, 1.1, 0.4, 3e-9, -8e-9, 2e-10),
            listOf(e.inclinationRad, e.ascendingNodeRad, e.argumentOfPerigeeRad, e.meanAnomalyRad,
                e.deltaMeanMotionRadPerSecond, e.ascendingNodeRateRadPerSecond, e.inclinationRateRadPerSecond))
        assertEquals(listOf(5e-6, 6e-6, 13.0, 12.0, 7e-8, 9e-8),
            listOf(e.cucRad, e.cusRad, e.crcMeters, e.crsMeters, e.cicRad, e.cisRad))
        assertEquals(listOf(1e-4, 2e-12, 3e-20, -4e-9),
            listOf(e.af0Seconds, e.af1SecondsPerSecond, e.af2SecondsPerSecondSquared, e.tgdSeconds))
    }
}
