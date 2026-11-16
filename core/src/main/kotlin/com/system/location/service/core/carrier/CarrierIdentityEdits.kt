package com.system.location.service.core.carrier

import java.util.Locale

/** Reconcile a multi-select result without resetting an existing application's SIM target. */
fun reconcileCarrierRoutes(
    existing: List<CarrierAppRoute>,
    selectedPackages: Collection<String>,
): List<CarrierAppRoute> {
    val selected = selectedPackages.distinctBy { it.lowercase(Locale.ROOT) }
    require(selected.size <= CarrierIdentityConfig.MAX_ROUTES) { "Too many carrier routes" }
    val byPackage = existing.associateBy { it.packageName.lowercase(Locale.ROOT) }
    return selected.map { name ->
        byPackage[name.lowercase(Locale.ROOT)] ?: CarrierAppRoute(name)
    }
}
