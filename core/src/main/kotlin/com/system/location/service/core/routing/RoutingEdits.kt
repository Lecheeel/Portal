package com.system.location.service.core.routing

/** Change only the exclusion list; fixed points and scenario assignments belong to other editors. */
fun RoutingConfig.withDeniedPackages(packages: Set<String>): RoutingConfig = copy(
    mode = if (packages.isNotEmpty() || routes.isNotEmpty() || globalScenarioId != null) {
        RoutingMode.PER_APP
    } else RoutingMode.GLOBAL,
    deniedPackages = packages,
)
