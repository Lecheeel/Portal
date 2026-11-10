package com.system.location.service.core

import com.system.location.service.core.environment.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.routing.*
import com.system.location.service.core.runtime.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PreparedRuntimeConfigurationTest {
    private val route = RoutingConfig(mode = RoutingMode.PER_APP, routes = listOf(AppRoute("test.app", point = Wgs84(1.0, 2.0))))
    private val environment = EnvironmentProfile(EnvironmentSourceType.REPLAY, generatedAtMillis = 100, seed = 42)
    private val initial = RuntimeSnapshot(configRevision = 7, environmentVersion = 3, routing = route,
        environment = environment, configuration = HookConfiguration(wifiFingerprintEnabled = true,
            wifiFingerprintCatalogJson = WifiFingerprintImporter.encodeJson(WifiFingerprintCatalog())))

    @Test fun partialUpdatePreservesUnspecifiedConfigurationAndOneRevision() {
        val result = PreparedRuntimeConfiguration.prepare(mapOf("min_satellites" to 20)).applyTo(initial)
        assertEquals(20, result.configuration.minimumSatellites)
        assertEquals(8L, result.configRevision)
        assertSame(route, result.routing)
        assertSame(environment, result.environment)
        assertEquals(3L, result.environmentVersion)
        assertTrue(result.configuration.wifiFingerprintEnabled)
        assertEquals(initial.configuration.wifiFingerprintCatalogJson, result.configuration.wifiFingerprintCatalogJson)
    }

    @Test fun explicitNullClearsAndBlankLegacyStringsClear() {
        for (clear in listOf(null, "", " ")) {
            val result = PreparedRuntimeConfiguration.prepare(mapOf("routing_json" to clear,
                "environment_json" to clear, "wifi_fingerprint_json" to clear, "gnss_capture" to clear)).applyTo(initial)
            assertNull(result.routing)
            assertNull(result.environment)
            assertNull(result.configuration.wifiFingerprintCatalogJson)
            assertNull(result.configuration.gnssSource)
            assertEquals(4L, result.environmentVersion)
        }
    }

    @Test fun invalidFieldRejectsWholeBatchWithoutPublication() {
        val badValues = mapOf<String, Any?>("min_satellites" to 129, "accuracy" to Float.NaN,
            "report_interval" to 49L, "sensor_variation" to 0.6, "environment_ap_count" to 2,
            "altitude" to Double.POSITIVE_INFINITY, "speed" to -1.0, "enable" to "true",
            "disable_fused_location" to null, "cell_mode" to "UNKNOWN", "environment_source" to null,
            "routing_json" to "{bad", "environment_json" to "{}", "wifi_fingerprint_json" to "broken",
            "gnss_capture" to "invalid")
        val store = RuntimeSnapshotStore(initial)
        for ((key, bad) in badValues) {
            assertThrows("Must reject $key", IllegalArgumentException::class.java) {
                val prepared = PreparedRuntimeConfiguration.prepare(mapOf("enable" to true, key to bad))
                store.update(prepared::applyTo)
            }
            assertSame(initial, store.current())
        }
    }

    @Test fun decodesCompleteBatchAndKeepsExistingActiveSpeed() {
        val prepared = PreparedRuntimeConfiguration.prepare(mapOf("enable" to true, "speed" to 100.0,
            "accuracy" to 8f, "enable_nmea" to false, "min_satellites" to 30,
            "routing_json" to Json.encodeToString(RoutingConfig.serializer(), route),
            "environment_json" to EnvironmentProfileCodec.encode(environment), "cell_mode" to "AUTO",
            "environment_source" to "REPLAY", "sensor_cadence_scale" to 1.5))
        val result = prepared.applyTo(RuntimeSnapshot(speedMps = 5.0))
        assertTrue(result.configuration.enabled)
        assertFalse(result.configuration.nmeaEnabled)
        assertEquals(30, result.configuration.minimumSatellites)
        assertEquals(8f, result.accuracyMeters)
        assertEquals(5.0, result.speedMps, 0.0)
        assertEquals(route, result.routing)
        assertEquals(environment, result.environment)
        assertEquals(CellMode.AUTO, result.cellMode)
        assertEquals(EnvironmentSourceMode.REPLAY, result.environmentSourceMode)
        assertEquals(1.5, result.configuration.sensorCadenceScale, 0.0)
        val stopped = PreparedRuntimeConfiguration.prepare(mapOf("enable" to false, "speed" to 100.0)).applyTo(result)
        assertEquals(100.0, stopped.speedMps, 0.0)
    }

    @Test fun preparedPatchMergesAgainstLatestStateAndCopiesCallerInput() {
        val input = mutableMapOf<String, Any?>("min_satellites" to 22)
        val prepared = PreparedRuntimeConfiguration.prepare(input)
        input["min_satellites"] = 33
        val sample = LocationSample(Wgs84(3.0, 4.0), 10.0, 5f, 2f, 90f, 1000, 1000)
        val latest = initial.copy(configRevision = 20, sample = sample, latitude = 3.0,
            configuration = initial.configuration.copy(enabled = true, nmeaEnabled = false))
        val result = prepared.applyTo(latest)
        assertSame(sample, result.sample)
        assertEquals(3.0, result.latitude, 0.0)
        assertEquals(21L, result.configRevision)
        assertEquals(22, result.configuration.minimumSatellites)
        assertTrue(result.configuration.enabled)
        assertFalse(result.configuration.nmeaEnabled)
        assertEquals(result, prepared.applyTo(latest))
    }
}
