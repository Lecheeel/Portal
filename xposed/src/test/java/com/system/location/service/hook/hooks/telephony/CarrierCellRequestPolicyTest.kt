package com.system.location.service.hook.hooks.telephony

import org.junit.Assert.*
import org.junit.Test

class CarrierCellRequestPolicyTest {
    @Test fun onlyTimeoutAndConfirmedNoNetworkCommandErrorsCanUseVirtualData() {
        assertTrue(CarrierCellRequestHook.canModelError(listOf(1, null, null)))
        for (detail in listOf("RADIO_NOT_AVAILABLE", "NO_NETWORK_FOUND")) {
            assertTrue(CarrierCellRequestHook.canModelError(listOf(2,
                "com.android.internal.telephony.CommandException", detail)))
        }
        assertFalse(CarrierCellRequestHook.canModelError(emptyList()))
        assertFalse(CarrierCellRequestHook.canModelError(listOf(2, null, null)))
        assertFalse(CarrierCellRequestHook.canModelError(listOf(2, "java.lang.SecurityException", "Permission denied")))
        assertFalse(CarrierCellRequestHook.canModelError(listOf(2, "CommandException", "GENERIC_FAILURE")))
        assertFalse(CarrierCellRequestHook.canModelError(listOf(42, "CommandException", "NO_NETWORK_FOUND")))
    }
}
