package com.system.location.service.core

import com.system.location.service.core.carrier.*
import org.junit.Assert.*
import org.junit.Test

class CarrierIdentityResolverTest {
    private val sim1 = SimCarrierProfile(1, countryIso = "CN", mcc = 460, mnc = 0,
        mncDigits = 2, carrierName = "Test CN")
    private val sim2 = SimCarrierProfile(2, countryIso = "US", mcc = 310, mnc = 260,
        carrierName = "Test US")
    private val config = CarrierIdentityConfig(sims = listOf(sim1, sim2))

    @Test fun globalUsesPhysicalSlotAndVirtualizesOnlyAnExplicitEmptySlotProfile() {
        assertEquals(sim2, CarrierIdentityResolver.profile(config, 2, setOf(2), listOf("android"), true))
        assertEquals(sim1.copy(virtual = true),
            CarrierIdentityResolver.profile(config, 1, setOf(2), listOf("android"), true))
        assertNull(CarrierIdentityResolver.profile(config, 3, setOf(1, 2, 3), listOf("app"), false))
    }

    @Test fun perAppRoutesCanUseAnExplicitVirtualSlotWithoutInventingUnconfiguredSlots() {
        val routed = config.copy(mode = CarrierIdentityMode.PER_APP,
            appRoutes = listOf(CarrierAppRoute("target.app", CarrierTarget.SIM2)))
        assertEquals(sim2, CarrierIdentityResolver.profile(routed, 1, setOf(1, 2), listOf("target.app"), false))
        assertEquals(sim2.copy(virtual = true),
            CarrierIdentityResolver.profile(routed, 1, setOf(1), listOf("target.app"), false))
        assertNull(CarrierIdentityResolver.profile(routed, 1, setOf(1, 2), listOf("other.app"), false))
        assertNull(CarrierIdentityResolver.profile(routed, 1, setOf(1, 2), listOf("target.app"), true))
    }

    @Test fun sharedUidRequiresUnambiguousRulesAndExplicitInheritKeepsSlot() {
        val routed = config.copy(mode = CarrierIdentityMode.PER_APP,
            appRoutes = listOf(CarrierAppRoute("a.app"), CarrierAppRoute("b.app")))
        assertEquals(sim2, CarrierIdentityResolver.profile(routed, 2, setOf(1, 2), listOf("a.app", "b.app"), false))
        assertNull(CarrierIdentityResolver.profile(routed, 2, setOf(1, 2), listOf("a.app", "unlisted.app"), false))
        assertNull(CarrierIdentityResolver.profile(routed, 2, setOf(1, 2), emptyList(), false))
    }
}
