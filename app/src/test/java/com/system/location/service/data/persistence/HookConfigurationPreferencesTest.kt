package com.system.location.service.data.persistence

import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class HookConfigurationPreferencesTest {
    @Test fun emptyPreferencesUseStableDefaultsAndExplicitlyClearOptionalPayloads() {
        val wire = HookConfigurationPreferences.read(emptyMap<String, Any>(), RuntimeSnapshot())
        val result = PreparedRuntimeConfiguration.prepare(wire).applyTo(RuntimeSnapshot())
        assertEquals(0, result.configuration.minimumSatellites)
        assertEquals(25f, result.accuracyMeters)
        assertEquals(500L, result.configuration.reportIntervalMs)
        for (key in listOf("wifi_fingerprint_json", "routing_json", "environment_json")) {
            assertTrue(wire.containsKey(key))
            assertNull(wire[key])
        }
    }

    @Test fun legacyPreferenceKeysAndInvertedFlagsKeepTheirMeaning() {
        val wire = HookConfigurationPreferences.read(mapOf("disableRegitserLocationListener" to true,
            "disableWifiScan" to false, "enableAGPS" to true, "enableRequestGeofence" to false,
            "enableGetFromLocation" to true, "minSatelliteCount" to 24, "loopBroadcastLocation" to true,
            "reportDuration" to 100, "environmentApCount" to 1, "speed" to 7f), RuntimeSnapshot())
        val result = PreparedRuntimeConfiguration.prepare(wire).applyTo(RuntimeSnapshot())
        assertTrue(result.configuration.disableRegisterLocationListener)
        assertFalse(result.configuration.wifiEnabled)
        assertTrue(result.configuration.allowPhysicalRawGnss)
        assertTrue(result.configuration.disableRequestGeofence)
        assertFalse(result.configuration.disableGetFromLocation)
        assertEquals(24, result.configuration.minimumSatellites)
        assertEquals(100L, result.configuration.reportIntervalMs)
        assertEquals(3, result.environmentApCount)
        assertEquals(7.0, result.speedMps, 0.0)
    }

    @Test fun batchDoesNotFollowLaterPreferenceOrFallbackChanges() {
        val preferences = mutableMapOf<String, Any>("minSatelliteCount" to 18)
        val fallback = RuntimeSnapshot(configuration = HookConfiguration(debugLog = true, disableFusedLocation = true))
        val wire = HookConfigurationPreferences.read(preferences.toMap(), fallback)
        preferences["minSatelliteCount"] = 30
        assertEquals(18, wire["min_satellites"])
        assertEquals(true, wire["enable_debug_log"])
        assertEquals(true, wire["disable_fused_location"])
        assertFalse(wire.containsKey("enable"))
    }

    @Test fun canonicalWifiPreferenceTakesPrecedenceOverLegacyAlias() {
        val wire = HookConfigurationPreferences.read(mapOf(
            "enableMockWifi" to true,
            "disableWifiScan" to false,
        ), RuntimeSnapshot())
        assertEquals(true, wire["enable_mock_wifi"])
    }

    @Test fun staleMinimumSatellitePreferenceIsClampedAtTheWireBoundary() {
        val wire = HookConfigurationPreferences.read(mapOf("minSatelliteCount" to 999), RuntimeSnapshot())
        assertEquals(128, wire["min_satellites"])
    }
}
