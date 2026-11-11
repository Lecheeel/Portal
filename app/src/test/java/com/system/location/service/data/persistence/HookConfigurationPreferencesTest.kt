package com.system.location.service.data.persistence

import com.system.location.service.core.runtime.*
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class HookConfigurationPreferencesTest {
    @Test fun localSkyReadsTheSameGnssBatchWithoutParsingUnrelatedPreferences() {
        val preferences = mapOf("gnssSimulationMode" to "AUTONOMOUS", "gnssVirtualSeed" to 12L,
            "minSatelliteCount" to 32)
        val fallback = RuntimeSnapshot()
        val gnss = HookConfigurationPreferences.readGnss(preferences + ("accuracy" to "invalid"), fallback)
        val full = HookConfigurationPreferences.read(preferences, fallback)
        assertEquals(full.filterKeys { it in gnss }, gnss)
        assertEquals(32, PreparedRuntimeConfiguration.prepare(gnss).applyTo(fallback).configuration.minimumSatellites)
        assertThrows(ClassCastException::class.java) {
            HookConfigurationPreferences.readGnss(mapOf("minSatelliteCount" to "32"), fallback)
        }
    }

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

    @Test fun staleAccuracyPreferenceIsClampedAtTheWireBoundary() {
        assertEquals(0.1f, HookConfigurationPreferences.read(mapOf("accuracy" to -5f), RuntimeSnapshot())["accuracy"])
        assertEquals(10_000f, HookConfigurationPreferences.read(mapOf("accuracy" to 50_000f), RuntimeSnapshot())["accuracy"])
    }

    @Test fun persistedGnssPolicyReachesTypedRuntimeWithoutLosingOtherPreferences() {
        val wire = HookConfigurationPreferences.read(mapOf("gnssSimulationMode" to "AUTONOMOUS",
            "gnssMinimumPolicy" to "GUARANTEE_QUANTITY", "gnssSimulationEnvironment" to "INDOOR",
            "gnssVirtualSeed" to Long.MIN_VALUE, "minSatelliteCount" to 32), RuntimeSnapshot())
        val result = PreparedRuntimeConfiguration.prepare(wire).applyTo(RuntimeSnapshot())
        assertEquals(GnssSimulationPolicy(GnssSimulationMode.AUTONOMOUS, GnssMinimumPolicy.GUARANTEE_QUANTITY,
            GnssSimulationEnvironment.INDOOR, Long.MIN_VALUE), result.configuration.gnssSimulation)
        assertEquals(32, result.configuration.minimumSatellites)
    }

    @Test fun missingGnssPreferencesPreserveFallbackPolicyAndOldInstallDefaultsToAuto() {
        val auto = HookConfigurationPreferences.read(emptyMap<String, Any>(), RuntimeSnapshot())
        assertEquals("AUTO", auto["gnss_mode"])
        val policy = GnssSimulationPolicy(mode = GnssSimulationMode.REPLAY, virtualSeed = 42)
        val fallback = RuntimeSnapshot(configuration = HookConfiguration(gnssSimulation = policy))
        val wire = HookConfigurationPreferences.read(emptyMap<String, Any>(), fallback)
        assertEquals(policy, PreparedRuntimeConfiguration.prepare(wire).applyTo(fallback).configuration.gnssSimulation)
    }
}
