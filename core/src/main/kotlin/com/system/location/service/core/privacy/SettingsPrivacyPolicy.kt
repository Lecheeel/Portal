package com.system.location.service.core.privacy

/** Pure SettingsProvider visibility rules shared by the hook and tests (P1-03). */
object SettingsPrivacyPolicy {
    const val FIRST_APP_UID = 10_000
    const val HIDDEN_VALUE = "0"

    val secureKeys = setOf("mock_location", "allow_mock_location")
    val globalKeys = setOf("development_settings_enabled", "adb_enabled")

    fun isRestricted(command: String, key: String): Boolean = when {
        command.equals("GET_secure", ignoreCase = true) -> key in secureKeys
        command.equals("GET_global", ignoreCase = true) -> key in globalKeys
        command.equals("GET_system", ignoreCase = true) -> key in globalKeys
        else -> false
    }

    fun isQueryKey(key: String?): Boolean = key != null && (key in secureKeys || key in globalKeys)

    fun shouldHide(callerUid: Int): Boolean = callerUid >= FIRST_APP_UID
}
