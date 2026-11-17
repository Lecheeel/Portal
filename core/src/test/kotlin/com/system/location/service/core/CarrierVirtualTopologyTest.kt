package com.system.location.service.core

import com.system.location.service.core.carrier.*
import org.junit.Assert.*
import org.junit.Test

class CarrierVirtualTopologyTest {
    private fun profile(slot: Int) = SimCarrierProfile(slot, virtual = true, countryIso = "AU",
        mcc = 505, mnc = 1, mncDigits = 2, carrierName = "Test", virtualSubscriptionId = 57 + slot * 13)

    @Test fun phoneAndOpaqueSubscriptionMappingAreInverseForEachVisibleSlot() {
        val profiles = listOf(profile(1), profile(2))
        for (phone in 0..1) {
            val id = CarrierVirtualTopology.subscriptionForPhone(profiles, phone)!!
            assertEquals(phone + 1, CarrierVirtualTopology.slotForSubscription(profiles, id))
        }
        assertNull(CarrierVirtualTopology.slotForSubscription(profiles, -1))
        assertNull(CarrierVirtualTopology.slotForSubscription(profiles, 0))
        assertNull(CarrierVirtualTopology.subscriptionForPhone(profiles, -1))
        assertNull(CarrierVirtualTopology.subscriptionForPhone(profiles, 2))
    }

    @Test fun legacyUnassignedIdsCannotBecomeVisibleThroughTopologyHelpers() {
        val profiles = listOf(profile(1).copy(virtualSubscriptionId = 0))
        assertNull(CarrierVirtualTopology.subscriptionForPhone(profiles, 0))
        assertNull(CarrierVirtualTopology.slotForSubscription(profiles, 900_000_001))
        assertArrayEquals(intArrayOf(42), CarrierVirtualTopology.activeIds(intArrayOf(42), profiles))
    }

    @Test fun simTwoOnlyAndHiddenProfilesDoNotInventSimOneOrActiveSubscriptions() {
        val profiles = listOf(profile(2))
        assertNull(CarrierVirtualTopology.subscriptionForPhone(profiles, 0))
        assertEquals(CarrierVirtualIdentity.subscriptionId(profile(2)), CarrierVirtualTopology.subscriptionForPhone(profiles, 1))
        assertNull(CarrierVirtualTopology.slotForSubscription(profiles, CarrierVirtualIdentity.subscriptionId(profile(1))))
        assertNull(CarrierVirtualTopology.subscriptionForPhone(listOf(profile(1).copy(enabled = false)), 0))
        assertNull(CarrierVirtualTopology.subscriptionForPhone(listOf(profile(1).copy(virtual = false)), 0))
        assertArrayEquals(intArrayOf(42, CarrierVirtualIdentity.subscriptionId(profile(2))),
            CarrierVirtualTopology.activeIds(intArrayOf(42, 42), profiles))
        assertArrayEquals(intArrayOf(42), CarrierVirtualTopology.activeIds(intArrayOf(42), emptyList()))
    }
}
