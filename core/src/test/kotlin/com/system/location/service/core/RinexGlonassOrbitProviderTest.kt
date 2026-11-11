package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RinexGlonassOrbitProviderTest {
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val epoch = Instant.parse("2026-11-08T00:15:00Z").toEpochMilli()
    private val record = RinexNavigationRecord(RinexNavigationSystem.GLONASS, 3,
        RinexNavigationEpoch(LocalDate.of(2026, 11, 8), 0, 15, 0), listOf(-1e-4, 2e-12, 900.0),
        listOf(listOf(19100.0, 1.1, 1e-9, 0.0), listOf(13400.0, -2.2, -2e-9, -7.0),
            listOf(10900.0, 0.3, 3e-9, 1.0)))
    private val policy = RinexGlonassSkyPolicy(Instant.parse("2014-01-15T00:00:00Z").toEpochMilli(), true)
    private val provenance = GnssOrbitProvenance("test-glonass", "1", "test", epoch, GnssOrbitSourceKind.BROADCAST_EPHEMERIS)
    private val request = GnssSkyRequest(epoch, Wgs84(31.0, 121.0), 0.0)
    private fun provider(vararg records: RinexNavigationRecord, policy: RinexGlonassSkyPolicy = this.policy) =
        RinexGlonassOrbitProvider(RinexNavigationFile("3.04", emptyList(), emptyList(), records.toList()), provenance, utc, policy)
    private fun change(row: Int, column: Int, value: Double?) = record.copy(orbit = record.orbit.mapIndexed { r, values ->
        if (r == row) values.mapIndexed { c, old -> if (c == column) value else old } else values
    })

    @Test fun nativeUtcSiUnitsDatumAndFcntoL1CarrierReachOneGlobalOrbit() {
        val batch = provider(record).positions(request)
        val position = batch.positions.single()
        assertEquals(19099999.999349408, position.position.xMeters, 1e-8)
        assertEquals(13399999.99981885, position.position.yMeters, 1e-8)
        assertEquals(10899999.994876489, position.position.zMeters, 1e-8)
        assertEquals(epoch, position.provenance.referenceEpochMillis)
        assertEquals((1_602_000_000.0 - 7 * 562_500.0).toFloat(), position.carrierHz)
        assertTrue(position.hasEphemeris)
        assertFalse(position.hasAlmanac)
        assertEquals(1, provider(record.copy(clock = listOf(-1e-4, 2e-12, 0.0)))
            .positions(request.copy(atMillis = epoch + 1000)).positions.size)
        assertEquals(batch.positions, provider(record).positions(request.copy(observer = Wgs84(-31.0, -59.0))).positions)
        for (at in listOf(epoch - 1, epoch + 900000, epoch + 604800000, utc.expiresAtMillis))
            assertTrue(provider(record).positions(request.copy(atMillis = at)).positions.isEmpty())
        assertTrue(provider(record, policy = policy.copy(allowApproximateItrfAlignment = false)).positions(request).positions.isEmpty())
        assertTrue(provider(record, policy = policy.copy(pz9011OperationalFromUtcMillis = epoch + 1)).positions(request).positions.isEmpty())
    }

    @Test fun leapSecondCrossingUses61SiSecondsRatherThan60PosixSeconds() {
        val before = record.copy(epoch = RinexNavigationEpoch(LocalDate.of(2016, 12, 31), 23, 59, 30),
            clock = listOf(-1e-4, 2e-12, 604770.0))
        val at = Instant.parse("2017-01-01T00:00:30Z").toEpochMilli()
        val position = provider(before).positions(request.copy(atMillis = at)).positions.single().position
        val native = RinexGlonassImporter.decode(before, GlonassReferenceFrame.PZ_90_11).orbit
        val expected = GlonassSkyFrameApproximation.pz9011ToItrf2008(
            GlonassBroadcastPropagator.propagate(native, 61.0).state!!.positionMeters)
        assertEquals(expected, position)
        val wrong = GlonassSkyFrameApproximation.pz9011ToItrf2008(
            GlonassBroadcastPropagator.propagate(native, 60.0).state!!.positionMeters)
        assertTrue(kotlin.math.abs(wrong.xMeters - position.xMeters) > 100)
    }

    @Test fun futureFramesMalformedLatestAndConflictingHealthCannotReviveOldUploads() {
        val invalid = change(0, 0, null).copy(clock = listOf(-1e-4, 2e-12, 1000.0))
        val source = provider(record, invalid)
        assertEquals(1, source.positions(request).positions.size)
        assertTrue(source.positions(request.copy(atMillis = epoch + 100000)).positions.isEmpty())
        assertTrue(provider(record, record.copy(clock = listOf(0.0, 0.0, null))).positions(request).positions.isEmpty())
        val conflict = provider(record, change(0, 3, 1.0)).positions(request)
        assertTrue(conflict.positions.isEmpty())
        assertEquals(setOf(Constellation.GLONASS to 3), conflict.blockedIdentities)
        assertTrue(provider(record, change(0, 3, 1.0)).positions(request.copy(atMillis = epoch + 900000)).blockedIdentities.isEmpty())
        assertTrue(provider(change(1, 3, 7.0)).positions(request).positions.isEmpty())
        assertTrue(provider(record.copy(svid = 26), change(0, 3, 1.0).copy(svid = 26)).positions(request).positions.isEmpty())
    }

    @Test fun exclusiveModernHealthFlagsAreUsedOnlyWhenTypeIsKnownAndApplicable() {
        fun extended(type: Double?, flags: Double) = record.copy(orbit = record.orbit + listOf(listOf(type, null, null, flags)))
        for (flags in listOf(2.0, 4.0, 6.0)) {
            val result = provider(extended(128.0, flags)).positions(request)
            assertTrue(result.positions.isEmpty())
            assertEquals(setOf(Constellation.GLONASS to 3), result.blockedIdentities)
        }
        assertEquals(1, provider(extended(null, 4.0)).positions(request).positions.size)
        assertEquals(1, provider(extended(0.0, 4.0)).positions(request).positions.size)
        assertEquals(1, provider(extended(128.0, 3.0)).positions(request).positions.size)
    }
}
