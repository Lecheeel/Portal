package com.system.location.service.core

import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.FingerprintWifiSource
import com.system.location.service.core.environment.WifiFingerprint
import com.system.location.service.core.environment.WifiFingerprintDb
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintWifiSourceTest {
    private val origin = Wgs84(31.2304, 121.4737)
    private val db = WifiFingerprintDb(listOf(
        WifiFingerprint("AA:BB:CC:DD:EE:01", origin, -50, 1_000),
        WifiFingerprint("AA:BB:CC:DD:EE:02", Wgs84(31.2310, 121.4740), -70, 1_000),
        WifiFingerprint("AA:BB:CC:DD:EE:03", Wgs84(31.3000, 121.5000), -40, 1_000),
    ))

    @Test fun selectsNearbyRecentFingerprints() {
        val profile = FingerprintWifiSource(db, radiusMeters = 500.0).profileAt(origin, 7, 2_000)!!
        assertEquals(EnvironmentSourceType.REPLAY, profile.source)
        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), profile.wifi.map { it.bssid })
        assertEquals(true, profile.wifi.first().connected)
    }

    @Test fun excludesStaleAndDistantEntries() {
        val profile = FingerprintWifiSource(db, radiusMeters = 50.0).profileAt(origin, 7, 2_000)!!
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), profile.wifi.map { it.bssid })
        assertNull(FingerprintWifiSource(db, radiusMeters = 50.0).profileAt(origin, 7, 1_000 + 31L * 24 * 60 * 60 * 1_000))
    }

    @Test fun respectsMaxApsAndEmptyRange() {
        val profile = FingerprintWifiSource(db, maxAps = 1).profileAt(origin, 7, 2_000)!!
        assertEquals(1, profile.wifi.size)
        assertNull(FingerprintWifiSource(db, radiusMeters = 10.0).profileAt(Wgs84(0.0, 0.0), 7, 2_000))
    }
}
