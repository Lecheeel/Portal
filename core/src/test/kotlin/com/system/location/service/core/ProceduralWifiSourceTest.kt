package com.system.location.service.core

import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.ProceduralWifiSource
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralWifiSourceTest {
    private val source = ProceduralWifiSource(EnvironmentConfig(apPerCell = 4))
    private val point = Wgs84(31.2304, 121.4737)
    private val atMillis = 1_790_000_000_000L

    @Test
    fun samePointAndSeedIsDeterministic() {
        val first = source.profileAt(point, seed = 42, atMillis = atMillis)
        val second = source.profileAt(point, seed = 42, atMillis = atMillis)
        assertEquals(first, second)
        assertEquals(EnvironmentSourceType.PROCEDURAL, first.source)
        assertEquals(4, first.wifi.size)
        assertEquals(1, first.wifi.count { it.connected })
        assertEquals(point, first.origin)
    }

    @Test
    fun differentSeedChangesAccessPointIdentity() {
        val first = source.profileAt(point, seed = 1, atMillis = atMillis)
        val second = source.profileAt(point, seed = 2, atMillis = atMillis)
        assertNotEquals(first.wifi.map { it.bssid }, second.wifi.map { it.bssid })
    }

    @Test
    fun generatedAccessPointsAreValidAndBounded() {
        val wifi = source.profileAt(point, seed = 7, atMillis = atMillis).wifi
        assertEquals(wifi.size, wifi.map { it.bssid.lowercase() }.distinct().size)
        wifi.forEach { ap ->
            assertTrue(WifiAp.BSSID_PATTERN.matches(ap.bssid))
            assertTrue(ap.bssid.lowercase() != WifiAp.SENTINEL_BSSID)
            assertTrue(ap.ssid.isNotBlank() && ap.ssid.length <= WifiAp.MAX_SSID_LENGTH)
            assertTrue(ap.rssi in WifiAp.MIN_RSSI..WifiAp.MAX_RSSI)
            assertTrue(ap.frequencyMhz in WifiAp.MIN_FREQUENCY_MHZ..WifiAp.MAX_FREQUENCY_MHZ)
            assertTrue(ap.channel in WifiAp.MIN_CHANNEL..WifiAp.MAX_CHANNEL)
        }
    }

    @Test
    fun nearbyPointsKeepTheSameApsAndSmoothRssi() {
        val near = Wgs84(point.latitude + 0.00001, point.longitude + 0.00001)
        val first = source.profileAt(point, seed = 11, atMillis = atMillis)
        val second = source.profileAt(near, seed = 11, atMillis = atMillis)
        assertEquals(first.wifi.map { it.bssid }, second.wifi.map { it.bssid })
        first.wifi.zip(second.wifi).forEach { (a, b) ->
            assertTrue("RSSI jumped ${a.rssi} -> ${b.rssi}", kotlin.math.abs(a.rssi - b.rssi) <= 6)
        }
    }

    @Test
    fun timeOnlyAffectsGenerationTimestamp() {
        val first = source.profileAt(point, seed = 11, atMillis = atMillis)
        val later = source.profileAt(point, seed = 11, atMillis = atMillis + 60_000)
        assertEquals(first.wifi, later.wifi)
        assertEquals(atMillis, first.generatedAtMillis)
        assertEquals(atMillis + 60_000, later.generatedAtMillis)
    }

    @Test
    fun noCellsOrBleAreProducedByWifiSource() {
        val profile = source.profileAt(point, seed = 1, atMillis = atMillis)
        assertTrue(profile.cells.isEmpty())
        assertTrue(profile.ble.isEmpty())
    }
}
