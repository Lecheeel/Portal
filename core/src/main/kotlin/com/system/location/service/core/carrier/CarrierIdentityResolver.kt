package com.system.location.service.core.carrier

/** Resolves an identity; an enabled profile in an empty real slot is a virtual SIM. */
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
        if (!physical && configured == null) return null
        if (config.mode == CarrierIdentityMode.GLOBAL) {
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
        return profile.copy(virtual = slot !in activeSlots)
    }
}
