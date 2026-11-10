package com.system.location.service.core

import com.system.location.service.core.settings.PortalSettingsSchema
import com.system.location.service.core.settings.SettingSpec
import com.system.location.service.core.settings.SettingType
import com.system.location.service.core.settings.SettingsSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsSchemaTest {
    private val schema = PortalSettingsSchema.schema

    @Test fun defaultsAreDeclared() {
        assertEquals("80.0", schema.defaultValue("altitude"))
        assertEquals("FULL_REPLAY", schema.defaultValue("cell_mode"))
        assertEquals("ORBITAL", schema.defaultValue("gnss_model"))
        assertEquals("true", schema.defaultValue("enable_nmea"))
        assertNull(schema.defaultValue("missing"))
    }

    @Test fun validatesPrimitiveAndEnumValues() {
        assertNull(schema.validate("altitude", "120.5"))
        assertNotNull(schema.validate("altitude", "-1"))
        assertNull(schema.validate("environment_ap_count", "4"))
        assertNotNull(schema.validate("environment_ap_count", "0"))
        assertNull(schema.validate("enable_mock_wifi", "true"))
        assertNotNull(schema.validate("enable_mock_wifi", "yes"))
        assertNull(schema.validate("cell_mode", "AUTO"))
        assertNotNull(schema.validate("cell_mode", "BROKEN"))
        assertNotNull(schema.validate("unknown", "x"))
        assertNull(schema.validate("enable_agps", "true"))
        assertNotNull(schema.validate("enable_agps", "1"))
        assertNull(schema.validate("physical_noise_seed", "42"))
        assertNull(schema.validate("speed_amplitude", "2.5"))
        assertNotNull(schema.validate("speed_amplitude", "99"))
        assertNull(schema.validate("wifi_fingerprint_json", "{}"))
        assertNotNull(schema.validate("wifi_fingerprint_json", ""))
        assertNotNull(schema.validate("wifi_fingerprint_json", "x".repeat(262_145)))
    }

    @Test fun nullMeansUseDefault() {
        assertNull(schema.validate("speed", null))
        assertEquals("3.05", schema.coerce("speed", null))
    }

    @Test fun coerceFallsBackForInvalidValues() {
        assertEquals("100", schema.coerce("report_interval", "1"))
        assertEquals("4", schema.coerce("environment_ap_count", "99"))
        assertEquals("ORBITAL", schema.coerce("gnss_model", "BROKEN"))
        assertEquals("true", schema.coerce("sim_ready", "TRUE"))
    }

    @Test fun duplicateOrInvalidSpecsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsSchema(listOf(
                SettingSpec("a", SettingType.INT, "1"),
                SettingSpec("a", SettingType.INT, "2"),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingSpec("a", SettingType.ENUM, "X")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingSpec("a", SettingType.ENUM, "B", choices = setOf("A"))
        }
    }
}
