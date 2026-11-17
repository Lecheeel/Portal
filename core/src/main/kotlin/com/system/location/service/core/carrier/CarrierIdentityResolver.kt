package com.system.location.service.core.carrier

/** Resolves real slots first; empty slots require the profile's explicit virtual-SIM permission. */
object CarrierIdentityResolver {
    fun profile(
        config: CarrierIdentityConfig,
        requestedSlot: Int,
        activeSlots: Set<Int>,
        callerPackages: Collection<String>,
        systemCaller: Boolean,
    ): SimCarrierProfile? {
        if (requestedSlot !in 1..2) return null
        val physical = requestedSlot in activeSlots
        val configured = config.profileForSlot(requestedSlot)
        if (config.mode == CarrierIdentityMode.GLOBAL) {
            if (!physical && (configured?.virtual != true || configured.virtualSubscriptionId == 0)) return null
            return configured?.copy(virtual = !physical)
        }
        if (systemCaller) return null
        val targets = callerPackages.map { config.targetFor(it, false) }.distinct()
        // Shared UIDs cannot securely distinguish their packages. Preserve real identity if any
        // sibling package is excluded/unlisted or their configured targets disagree.
        if (targets.size != 1 || targets.single() == CarrierTarget.REAL) return null
        val slot = when (targets.single()) {
            CarrierTarget.SIM1 -> 1
            CarrierTarget.SIM2 -> 2
            CarrierTarget.INHERIT -> requestedSlot
            CarrierTarget.REAL -> return null
        }
        val profile = config.profileForSlot(slot) ?: return null
        if (slot !in activeSlots && (!profile.virtual || profile.virtualSubscriptionId == 0)) return null
        return profile.copy(virtual = slot !in activeSlots)
    }
}
