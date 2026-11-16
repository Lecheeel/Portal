package com.system.location.service.core.carrier

/** Resolves an identity without inventing a SIM in an empty physical slot. */
object CarrierIdentityResolver {
    fun profile(
        config: CarrierIdentityConfig,
        requestedSlot: Int,
        activeSlots: Set<Int>,
        callerPackages: Collection<String>,
        systemCaller: Boolean,
    ): SimCarrierProfile? {
        if (requestedSlot !in activeSlots || requestedSlot !in 1..2) return null
        if (config.mode == CarrierIdentityMode.GLOBAL) return config.profileForSlot(requestedSlot)
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
        return config.profileForSlot(slot).takeIf { slot in activeSlots }
    }
}
