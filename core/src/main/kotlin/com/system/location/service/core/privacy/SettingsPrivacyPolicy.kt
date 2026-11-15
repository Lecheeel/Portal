package com.system.location.service.core.privacy

/** Pure SettingsProvider visibility rules shared by the hook and tests (P1-03). */
object SettingsPrivacyPolicy {
    const val FIRST_APP_UID = 10_000
    const val HIDDEN_VALUE = "0"

    val secureKeys = setOf("mock_location", "allow_mock_location")

    fun isRestricted(command: String, key: String): Boolean =
        command.equals("GET_secure", ignoreCase = true) && key in secureKeys

    fun isQueryKey(key: String?): Boolean = key != null && key in secureKeys

    fun shouldHide(callerUid: Int): Boolean = callerUid >= FIRST_APP_UID
}
