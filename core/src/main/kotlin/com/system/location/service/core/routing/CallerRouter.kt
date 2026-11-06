package com.system.location.service.core.routing

/** PackageManager view used by the system_server caller resolver (P5-02). */
interface PackageUidLookup {
    fun packagesForUid(uid: Int): List<String>

    fun isSystemPackage(packageName: String): Boolean
}

/**
 * Resolves a Binder caller UID to the routing table without touching Android APIs.
 *
 * Shared UIDs can own several packages; a route matches if any package matches, and a system
 * package in the UID wins over the per-app table so framework services keep real locations.
 */
class CallerRouter(
    private val table: RoutingTable,
    private val lookup: PackageUidLookup,
) {
    fun decision(uid: Int): RoutingDecision {
        val packages = lookup.packagesForUid(uid)
        val isSystem = packages.any(lookup::isSystemPackage)
        return table.decision(uid, packages, isSystem)
    }
}
