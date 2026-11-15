package com.system.location.service.core

import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentReplayPolicy
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.ReplayEnvironmentSource
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
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
        originAccuracyMeters = 10.0,
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
    fun nearbyTargetKeepsProfileWithinMeasuredRange() {
        val replay = ReplayEnvironmentSource(captured)
        val nearby = Wgs84(origin.latitude + 0.00004, origin.longitude) // ~4 m
        val result = replay.profileAt(nearby, seed = 1, atMillis = 100).connectedWifi()!!
        assertEquals(ap.rssi, result.rssi)
        assertEquals(ap.bssid, result.bssid)
        assertEquals(ap.ssid, result.ssid)
        assertTrue(result.rssi in WifiAp.MIN_RSSI..WifiAp.MAX_RSSI)
    }

    @Test
    fun missingOriginOrAccuracyIsNotApplicable() {
        val unlocated = captured.copy(origin = null, originAccuracyMeters = null)
        assertFalse(EnvironmentReplayPolicy.isApplicable(unlocated, origin))
        assertFalse(EnvironmentReplayPolicy.isApplicable(captured.copy(originAccuracyMeters = null), origin))
        assertThrows(IllegalArgumentException::class.java) {
            ReplayEnvironmentSource(unlocated).profileAt(origin, 1, 100)
        }
    }

    @Test
    fun targetOutsideMeasuredRangeIsRejected() {
        val far = Wgs84(origin.latitude + 0.01, origin.longitude)
        assertFalse(EnvironmentReplayPolicy.isApplicable(captured, far))
        assertThrows(IllegalArgumentException::class.java) {
            ReplayEnvironmentSource(captured).profileAt(far, 1, 100)
        }
    }

    @Test
    fun onlyReplayProfilesAreAccepted() {
        assertThrows(IllegalArgumentException::class.java) {
            ReplayEnvironmentSource(captured.copy(source = EnvironmentSourceType.PROCEDURAL))
        }
    }
}
