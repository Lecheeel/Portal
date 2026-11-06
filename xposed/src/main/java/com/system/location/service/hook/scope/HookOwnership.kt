package com.system.location.service.hook.scope

/** Ownership audit for one hooked process: which entry points should exist and which responded. */
data class HookOwnershipReport(
    val scope: HookScope,
    val expected: List<String>,
    val recorded: List<String>,
    val missing: List<String>,
    val installed: Int,
    val matched: Int,
    val failed: Int,
    val skipped: Int,
) {
    val healthy: Boolean get() = missing.isEmpty() && failed == 0
}

/**
 * Static ownership table for P0-04.
 *
 * The table is intentionally small: it names the top-level hook installers. Method-level hook points
 * still live in [HookStatusRegistry] under their own keys and show up in `hook_details`.
 */
object HookOwnership {
    private val expectedByScope: Map<HookScope, List<String>> = mapOf(
        HookScope.SYSTEM_SERVER to listOf(
            "system-location",
            "appops",
            "provider-registry",
            "availability",
            "telephony-registry",
            "wifi",
            "fused",
            "sensor",
            "system-sdk",
        ),
        HookScope.PHONE_PROCESS to listOf("phone"),
        HookScope.FUSED_PROCESS to listOf("fused"),
        HookScope.VENDOR_LOCATION_PROCESS to listOf("oplus", "xiaomi"),
        HookScope.SETTINGS_PROVIDER to listOf("settings"),
        HookScope.TARGET_APP to emptyList(),
    )

    fun expectedFor(scope: HookScope): List<String> = expectedByScope[scope].orEmpty()

    fun audit(scope: HookScope, statuses: Map<String, HookStatus>): HookOwnershipReport {
        val expected = expectedFor(scope)
        val recorded = expected.filter { statuses.containsKey(it) }
        val missing = expected.filterNot { statuses.containsKey(it) }
        val relevant = expected.mapNotNull { statuses[it] }
        return HookOwnershipReport(
            scope = scope,
            expected = expected,
            recorded = recorded,
            missing = missing,
            installed = relevant.count { it.installed },
            matched = relevant.count { it.matched },
            failed = relevant.count { it.failed },
            skipped = relevant.count { it.skipped },
        )
    }
}
