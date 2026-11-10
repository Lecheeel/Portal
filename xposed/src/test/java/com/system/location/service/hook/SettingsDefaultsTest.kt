package com.system.location.service.hook

import com.system.location.service.core.settings.PortalSettingsSchema
import com.system.location.service.hook.utils.FakeLoc
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {
    private val schema = PortalSettingsSchema.schema

    @Test fun schemaDefaultsMatchRuntimeDefaults() {
        assertEquals(FakeLoc.altitude.toString(), schema.defaultValue("altitude"))
        assertEquals(FakeLoc.speed.toString(), schema.defaultValue("speed"))
        assertEquals(FakeLoc.accuracy.toString(), schema.defaultValue("accuracy"))
        assertEquals(FakeLoc.reportIntervalMs.toString(), schema.defaultValue("report_interval"))
        assertEquals(FakeLoc.minSatellites.toString(), schema.defaultValue("min_satellites"))
        assertEquals(FakeLoc.environmentApCount.toString(), schema.defaultValue("environment_ap_count"))
        assertEquals(FakeLoc.cellMode.name, schema.defaultValue("cell_mode"))
        assertEquals(FakeLoc.environmentSourceMode.name, schema.defaultValue("environment_source"))
        assertEquals(FakeLoc.gnssModelMode.name, schema.defaultValue("gnss_model"))
        assertEquals(FakeLoc.simReady.toString(), schema.defaultValue("sim_ready"))
        assertEquals(FakeLoc.enableDebugLog.toString(), schema.defaultValue("enable_debug_log"))
        assertEquals(FakeLoc.enableNMEA.toString(), schema.defaultValue("enable_nmea"))
        assertEquals(FakeLoc.enableMockWifi.toString(), schema.defaultValue("enable_mock_wifi"))
        assertEquals(FakeLoc.enablePhysicalNoise.toString(), schema.defaultValue("enable_physical_noise"))
        assertEquals("false", schema.defaultValue("wifi_fingerprint_enabled"))
    }
}
