package com.system.location.service.core.routing

/** Change only the exclusion list; fixed points and scenario assignments belong to other editors. */
fun RoutingConfig.withDeniedPackages(packages: Set<String>): RoutingConfig = copy(
    mode = if (packages.isNotEmpty() || routes.isNotEmpty() || globalScenarioId != null) {
        RoutingMode.PER_APP
    } else RoutingMode.GLOBAL,
    deniedPackages = packages,
)

/** Replace assignments for this point, including removals, without disturbing other points. */
fun RoutingConfig.withPointAssignments(
    point: com.system.location.service.core.geo.Wgs84,
    packages: Set<String>,
): RoutingConfig {
    val selected = packages.distinctBy { it.lowercase() }
    val normalized = selected.map { it.lowercase() }.toSet()
    val nextRoutes = routes.filterNot { it.point == point || it.packageName.lowercase() in normalized } +
        selected.map { AppRoute(it, point = point) }
    return copy(routes = nextRoutes).withDeniedPackages(deniedPackages)
}
