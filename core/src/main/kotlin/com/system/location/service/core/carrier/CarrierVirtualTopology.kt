package com.system.location.service.core.carrier

/** Only explicitly visible virtual profiles participate; absent slots remain absent. */
object CarrierVirtualTopology {
    fun slotForSubscription(profiles: List<SimCarrierProfile>, subId: Int): Int? =
        profiles.firstOrNull { it.virtual && it.enabled &&
            CarrierVirtualIdentity.subscriptionId(it.slot) == subId }?.slot

    fun subscriptionForPhone(profiles: List<SimCarrierProfile>, phoneId: Int): Int? =
        profiles.firstOrNull { it.virtual && it.enabled && it.slot == phoneId + 1 }
            ?.let { CarrierVirtualIdentity.subscriptionId(it.slot) }

    fun activeIds(physicalIds: IntArray, profiles: List<SimCarrierProfile>): IntArray =
        (physicalIds.toList() + profiles.filter { it.virtual && it.enabled }
            .sortedBy { it.slot }.map { CarrierVirtualIdentity.subscriptionId(it.slot) })
            .distinct().toIntArray()
}
