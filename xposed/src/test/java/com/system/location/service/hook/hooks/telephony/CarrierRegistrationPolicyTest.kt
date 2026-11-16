package com.system.location.service.hook.hooks.telephony

import org.junit.Assert.*
import org.junit.Test

class CarrierRegistrationPolicyTest {
    @Test fun notifyNowIsReadAfterEventsInsteadOfLocationPermissionFlags() {
        fun arguments(fine: Boolean, coarse: Boolean, notify: Boolean): List<Any?> =
            listOf(fine, coarse, 47, "example.app", null, Any(), intArrayOf(1, 9, 11), notify)
        assertTrue(CarrierRegistrationPolicy.notifyNow(arguments(false, false, true)))
        assertFalse(CarrierRegistrationPolicy.notifyNow(arguments(true, true, false)))
        assertTrue(CarrierRegistrationPolicy.notifyNow(arguments(true, false, true)))
        assertFalse(CarrierRegistrationPolicy.notifyNow(arguments(false, true, false)))
    }
    @Test fun unknownSignatureDoesNotInventInitialDelivery() {
        assertFalse(CarrierRegistrationPolicy.notifyNow(emptyList()))
        assertFalse(CarrierRegistrationPolicy.notifyNow(listOf(true, false, intArrayOf(1))))
        assertFalse(CarrierRegistrationPolicy.notifyNow(listOf(intArrayOf(1), "true")))
        assertTrue(CarrierRegistrationPolicy.notifyNow(listOf(47, Any(), intArrayOf(1), true)))
    }
}
