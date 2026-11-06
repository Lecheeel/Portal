package com.system.location.service.core

import com.system.location.service.core.environment.WifiFingerprint
import com.system.location.service.core.environment.WifiFingerprintDb
import com.system.location.service.core.environment.WifiObservation
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WifiFingerprintDbTest {
    private val a = WifiFingerprint("AA:BB:CC:DD:EE:01", Wgs84(31.2300, 121.4700), -50, 1_000)
    private val b = WifiFingerprint("AA:BB:CC:DD:EE:02", Wgs84(31.2310, 121.4720), -70, 1_000)

    @Test fun exactMatchReturnsStoredPosition() {
        val result = WifiFingerprintDb(listOf(a)).query(listOf(WifiObservation(a.bssid, -50)), 2_000)
        assertEquals(a.position.latitude, result!!.latitude, 0.000001)
        assertEquals(a.position.longitude, result.longitude, 0.000001)
    }

    @Test fun unknownObservationsReturnNull() {
        assertNull(WifiFingerprintDb(listOf(a)).query(listOf(WifiObservation("AA:BB:CC:DD:EE:03", -60)), 2_000))
        assertNull(WifiFingerprintDb().query(emptyList(), 2_000))
    }

    @Test fun staleFingerprintIsIgnored() {
        val now = a.observedAtMillis + WifiFingerprintDb.DEFAULT_MAX_AGE_MILLIS + 1
        assertNull(WifiFingerprintDb(listOf(a)).query(listOf(WifiObservation(a.bssid, -50)), now))
    }

    @Test fun twoMatchesAreWeightedTowardCloserRssi() {
        val result = WifiFingerprintDb(listOf(a, b)).query(
            listOf(WifiObservation(a.bssid, -50), WifiObservation(b.bssid, -85)),
            2_000,
        )!!
        assertEquals(a.position.latitude, result.latitude, 0.0005)
        assertEquals(a.position.longitude, result.longitude, 0.001)
    }

    @Test fun addAndRemoveAreImmutableAndCaseInsensitive() {
        val original = WifiFingerprintDb()
        val added = original.add(a)
        assertEquals(1, added.entries.size)
        assertEquals(0, original.entries.size)
        assertEquals(0, added.remove("aa:bb:cc:dd:ee:01").entries.size)
    }

    @Test fun invalidObservationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WifiObservation("bad", -50) }
        assertThrows(IllegalArgumentException::class.java) { WifiObservation(a.bssid, 10) }
        assertThrows(IllegalArgumentException::class.java) { a.copy(observedAtMillis = -1) }
    }
}
