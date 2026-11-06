package com.system.location.service.core.routing

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingMode { GLOBAL, PER_APP }

@Serializable
data class AppRoute(
    val packageName: String,
    val scenarioId: String,
    val uid: Int? = null,
    val enabled: Boolean = true,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(packageName.length <= 255) { "packageName too long" }
        require(scenarioId.isNotBlank()) { "scenarioId must not be blank" }
        uid?.let { require(it >= 0) { "uid must be non-negative" } }
    }
}

@Serializable
data class RoutingConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val mode: RoutingMode = RoutingMode.GLOBAL,
    val globalScenarioId: String? = null,
    val routes: List<AppRoute> = emptyList(),
    val deniedPackages: Set<String> = emptySet(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported routing schema: $schemaVersion" }
        require(globalScenarioId == null || globalScenarioId.isNotBlank()) { "globalScenarioId must not be blank" }
        require(routes.size <= MAX_ROUTES) { "Too many routes: ${routes.size}" }
        require(deniedPackages.size <= MAX_DENIED) { "Too many denied packages: ${deniedPackages.size}" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ROUTES = 1_024
        const val MAX_DENIED = 256
    }
}

sealed class RoutingDecision {
    data class Scenario(val scenarioId: String) : RoutingDecision()
    data object Global : RoutingDecision()
    data object Deny : RoutingDecision()
}

/**
 * Pure per-app routing table (P5-01).
 *
 * Precedence: disabled/only-GLOBAL → system caller bypass → explicit deny → exact package route →
 * UID route → global scenario → real global location. The table never decides *which* scenario a
 * package should get; it only resolves a stable ID supplied by the config.
 */
class RoutingTable(private val config: RoutingConfig = RoutingConfig()) {
    private val packageRoutes: Map<String, AppRoute>
    private val uidRoutes: Map<Int, AppRoute>

    init {
        val enabledRoutes = config.routes.filter { it.enabled }
        val packages = enabledRoutes.map { it.packageName.lowercase() }
        require(packages.distinct().size == packages.size) { "Duplicate package route" }
        val uids = enabledRoutes.mapNotNull { it.uid }
        require(uids.distinct().size == uids.size) { "Duplicate uid route" }
        packageRoutes = enabledRoutes.associateBy { it.packageName.lowercase() }
        uidRoutes = enabledRoutes.mapNotNull { route -> route.uid?.let { it to route } }.toMap()
    }

    fun decision(uid: Int, packageName: String?, isSystemApp: Boolean): RoutingDecision {
        if (config.mode == RoutingMode.GLOBAL) return RoutingDecision.Global
        if (isSystemApp || uid < FIRST_APP_UID) return RoutingDecision.Global
        val normalized = packageName?.lowercase()
        if (normalized != null && normalized in config.deniedPackages.map { it.lowercase() }) {
            return RoutingDecision.Deny
        }
        val packageRoute = normalized?.let { packageRoutes[it] }
        if (packageRoute != null) return RoutingDecision.Scenario(packageRoute.scenarioId)
        val uidRoute = uidRoutes[uid]
        if (uidRoute != null) return RoutingDecision.Scenario(uidRoute.scenarioId)
        return config.globalScenarioId?.let { RoutingDecision.Scenario(it) } ?: RoutingDecision.Global
    }

    fun routes(): List<AppRoute> = config.routes.toList()

    fun config(): RoutingConfig = config

    companion object {
        const val FIRST_APP_UID = 10_000
    }
}
