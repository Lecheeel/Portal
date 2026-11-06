package com.system.location.service.core

import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.ReplayEnvironmentSource
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEnvironmentSourceTest {
    private val origin = Wgs84(31.2304, 121.4737)
    private val ap = WifiAp(
        ssid = "ChinaNet-1234",
        bssid = "A4:50:46:12:34:56",
        rssi = -55,
        frequencyMhz = 2412,
        channel = 1,
        connected = true,
    )
    private val captured = EnvironmentProfile(
        source = EnvironmentSourceType.REPLAY,
        origin = origin,
        generatedAtMillis = 1,
        seed = 1,
        wifi = listOf(ap),
    )

    @Test
    fun sameOriginKeepsRssiAndIdentity() {
        val replay = ReplayEnvironmentSource(captured)
        val result = replay.profileAt(origin, seed = 2, atMillis = 100)
        assertEquals(ap, result.connectedWifi())
        assertEquals(EnvironmentSourceType.REPLAY, result.source)
        assertEquals(origin, result.origin)
        assertEquals(2L, result.seed)
    }

    @Test
    fun movingAwayLowersRssiButKeepsBssid() {
        val replay = ReplayEnvironmentSource(captured)
        val far = Wgs84(origin.latitude + 0.01, origin.longitude) // ~1.1 km
        val result = replay.profileAt(far, seed = 1, atMillis = 100).connectedWifi()!!
        assertTrue(result.rssi < ap.rssi)
        assertEquals(ap.bssid, result.bssid)
        assertEquals(ap.ssid, result.ssid)
        assertTrue(result.rssi in WifiAp.MIN_RSSI..WifiAp.MAX_RSSI)
    }

    @Test
    fun rssiIsClampedForVeryFarTarget() {
        val replay = ReplayEnvironmentSource(captured)
        val far = Wgs84(0.0, 0.0)
        val result = replay.profileAt(far, seed = 1, atMillis = 100).connectedWifi()!!
        assertEquals(WifiAp.MIN_RSSI, result.rssi)
    }

    @Test
    fun missingOriginUsesTargetWithoutChangingRssi() {
        val replay = ReplayEnvironmentSource(captured.copy(origin = null))
        val result = replay.profileAt(Wgs84(30.0, 120.0), seed = 1, atMillis = 100)
        assertEquals(ap.rssi, result.connectedWifi()!!.rssi)
    }

    @Test
    fun onlyReplayProfilesAreAccepted() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplayEnvironmentSource(captured.copy(source = EnvironmentSourceType.PROCEDURAL))
        }
    }
}
