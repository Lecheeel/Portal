package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.core.runtime.HookConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierHardwareSubscriptionPolicyTest {
    private val sim = SimCarrierProfile(1, countryIso = "CN", mcc = 460, mnc = 0,
        mncDigits = 2, carrierName = "Test Mobile")
    @Test fun bootAndStoppedSimulationPreserveThePlatformActiveIdSnapshot() {
        assertFalse(CarrierHardwareSubscriptionHook.requiresPhysicalQuery(HookConfiguration()))
        val virtual = HookConfiguration(carrierIdentityEnabled = true,
            carrierIdentity = CarrierIdentityConfig(sims = listOf(sim.copy(virtual = true))))
        assertFalse(CarrierHardwareSubscriptionHook.requiresPhysicalQuery(virtual))
        assertFalse(CarrierHardwareSubscriptionHook.requiresPhysicalQuery(virtual.copy(enabled = true,
            carrierIdentityEnabled = false)))
        assertTrue(CarrierHardwareSubscriptionHook.requiresPhysicalQuery(virtual.copy(enabled = true)))
    }

    @Test fun physicalOnlyIdentityDoesNotReplaceTheHardwareSubscriptionList() {
        val physical = HookConfiguration(enabled = true, carrierIdentityEnabled = true,
            carrierIdentity = CarrierIdentityConfig(sims = listOf(sim)))
        assertFalse(CarrierHardwareSubscriptionHook.requiresPhysicalQuery(physical))
        val disabledVirtual = physical.copy(carrierIdentity = CarrierIdentityConfig(sims = listOf(
            sim.copy(virtual = true, enabled = false))))
        assertFalse(CarrierHardwareSubscriptionHook.requiresPhysicalQuery(disabledVirtual))
    }
}
