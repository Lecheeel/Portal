package com.system.location.service.detector.model

enum class CheckStatus { PASS, FAIL, UNKNOWN }

/** Stable v1 check identifiers from docs/roadmap/06-validation.md. */
object CheckIds {
    const val LOCATION_MOCK = "C01"
    const val APP_OPS = "C02"
    const val SETTINGS = "C03"
    const val PROVIDER_LIST = "C04"
    const val LOCATION_FIELDS = "C05"
    const val GNSS_STATUS = "C06"
    const val NMEA = "C07"
    const val WIFI = "C08"
    const val CELL = "C09"
    const val SENSOR = "C10"
    const val ACTIVITY = "C11"
    const val TRAJECTORY = "C12"
    const val PACKAGE_IDENTITY = "C13"
    const val ROOT_TRACE = "C14"
    const val NETWORK_CONSISTENCY = "C15"
}

data class CheckResult(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val evidence: List<String> = emptyList(),
    val blocking: Boolean = false,
)

/**
 * A detector check is a pure "read evidence, return a verdict" unit.
 *
 * Checks must never throw: [CheckRunner] converts unexpected exceptions into UNKNOWN so one broken
 * API cannot hide the rest of the report.
 */
interface Check {
    val id: String
    val title: String
    val blocking: Boolean get() = false

    fun run(context: CheckContext): CheckResult

    fun pass(detail: String, evidence: List<String> = emptyList()): CheckResult =
        CheckResult(id, title, CheckStatus.PASS, detail, evidence, blocking)

    fun fail(detail: String, evidence: List<String> = emptyList()): CheckResult =
        CheckResult(id, title, CheckStatus.FAIL, detail, evidence, blocking)

    fun unknown(detail: String, evidence: List<String> = emptyList()): CheckResult =
        CheckResult(id, title, CheckStatus.UNKNOWN, detail, evidence, blocking)
}
