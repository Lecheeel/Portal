package com.system.location.service.detector

import android.content.Context
import android.content.SharedPreferences

/** Shared preferences are the only persistent detector state; nothing leaves the device. */
class DetectorSettings private constructor(private val preferences: SharedPreferences) {

    var targetPackage: String
        get() = preferences.getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_TARGET_PACKAGE
        set(value) {
            preferences.edit().putString(KEY_TARGET_PACKAGE, value.trim()).apply()
        }

    var sampleSeconds: Int
        get() = preferences.getInt(KEY_SAMPLE_SECONDS, DEFAULT_SAMPLE_SECONDS).coerceIn(MIN_SAMPLE_SECONDS, MAX_SAMPLE_SECONDS)
        set(value) {
            preferences.edit().putInt(KEY_SAMPLE_SECONDS, value.coerceIn(MIN_SAMPLE_SECONDS, MAX_SAMPLE_SECONDS)).apply()
        }

    var testGms: Boolean
        get() = preferences.getBoolean(KEY_TEST_GMS, false)
        set(value) {
            preferences.edit().putBoolean(KEY_TEST_GMS, value).apply()
        }

    var checkNetworkIp: Boolean
        get() = preferences.getBoolean(KEY_CHECK_NETWORK_IP, false)
        set(value) {
            preferences.edit().putBoolean(KEY_CHECK_NETWORK_IP, value).apply()
        }

    var readInstalledApps: Boolean
        get() = preferences.getBoolean(KEY_READ_INSTALLED_APPS, false)
        set(value) {
            preferences.edit().putBoolean(KEY_READ_INSTALLED_APPS, value).apply()
        }

    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.system.location.service"
        const val DEFAULT_SAMPLE_SECONDS = 60
        const val MIN_SAMPLE_SECONDS = 5
        const val MAX_SAMPLE_SECONDS = 300

        private const val PREFS = "detector_settings"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_SAMPLE_SECONDS = "sample_seconds"
        private const val KEY_TEST_GMS = "test_gms"
        private const val KEY_CHECK_NETWORK_IP = "check_network_ip"
        private const val KEY_READ_INSTALLED_APPS = "read_installed_apps"

        fun get(context: Context): DetectorSettings =
            DetectorSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
