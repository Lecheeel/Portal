package com.system.location.service.core

import com.system.location.service.core.privacy.SettingsPrivacyPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPrivacyPolicyTest {
    @Test fun restrictsSecureAndGlobalCommands() {
        assertTrue(SettingsPrivacyPolicy.isRestricted("GET_secure", "mock_location"))
        assertTrue(SettingsPrivacyPolicy.isRestricted("GET_secure", "allow_mock_location"))
        assertTrue(SettingsPrivacyPolicy.isRestricted("GET_global", "development_settings_enabled"))
        // Developer options and adb are hidden together: reporting one real and one hidden would
        // be a cross-checkable contradiction on a device with adb enabled.
        assertTrue(SettingsPrivacyPolicy.isRestricted("GET_system", "adb_enabled"))
        assertTrue(SettingsPrivacyPolicy.isRestricted("GET_global", "adb_enabled"))
        assertFalse(SettingsPrivacyPolicy.isRestricted("GET_secure", "adb_enabled"))
        assertFalse(SettingsPrivacyPolicy.isRestricted("GET_global", "mock_location"))
        assertFalse(SettingsPrivacyPolicy.isRestricted("PUT_secure", "mock_location"))
    }

    @Test fun queryKeysAndUidGate() {
        assertTrue(SettingsPrivacyPolicy.isQueryKey("mock_location"))
        assertTrue(SettingsPrivacyPolicy.isQueryKey("development_settings_enabled"))
        assertFalse(SettingsPrivacyPolicy.isQueryKey("unknown"))
        assertFalse(SettingsPrivacyPolicy.isQueryKey(null))
        assertFalse(SettingsPrivacyPolicy.shouldHide(1000))
        assertTrue(SettingsPrivacyPolicy.shouldHide(10_000))
    }
}
