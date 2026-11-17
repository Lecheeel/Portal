package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.carrier.SimCarrierProfile
import org.junit.Assert.*
import org.junit.Test

class CarrierStatusBarRenderTest {
    private fun sim(slot: Int, name: String) = SimCarrierProfile(slot, virtual = true,
        countryIso = "US", mcc = 310, mnc = 260, carrierName = name)

    @Test fun bothSlotsRenderTemporarilyAndOffUsesUntouchedLatestRomCaches() {
        val controller = Controller()
        val originalCarrier = controller.mCarrier
        val originalDisabled = controller.mCardDisable
        val originalCustom = controller.mCustomCarrier
        assertEquals("One | Two", CarrierStatusBarHook.withVirtualCarriers(controller,
            listOf(sim(1, "One"), sim(2, "Two"))) {
            assertFalse(controller.mCardDisable.any { it })
            assertFalse(controller.mSimError.any { it })
            assertFalse(controller.mEmergencyOnly)
            assertTrue(controller.mCustomCarrier.all { it == null })
            controller.mCarrier.joinToString(" | ")
        })
        assertSame(originalCarrier, controller.mCarrier)
        assertTrue(controller.mEmergencyOnly)
        assertSame(originalCustom, controller.mCustomCarrier)
        assertSame(originalDisabled, controller.mCardDisable)
        controller.mCarrier[0] = "Updated by ROM"
        assertEquals("Updated by ROM", CarrierStatusBarHook.withVirtualCarriers(controller, emptyList()) {
            controller.mCarrier[0]
        })
    }

    @Test fun simTwoDoesNotChangeSimOneAndExceptionsRestoreEveryReference() {
        val controller = Controller()
        val originalCarrier = controller.mCarrier
        val originalErrors = controller.mSimError
        val failure = IllegalStateException("Renderer rejected update")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            CarrierStatusBarHook.withVirtualCarriers(controller, listOf(sim(2, "Two"))) {
                assertEquals("Real one", controller.mCarrier[0])
                assertTrue(controller.mCardDisable[0])
                assertEquals("Two", controller.mCarrier[1])
                assertFalse(controller.mEmergencyOnly)
                throw failure
            }
        })
        assertSame(originalCarrier, controller.mCarrier)
        assertTrue(controller.mEmergencyOnly)
        assertSame(originalErrors, controller.mSimError)
        assertTrue(controller.mCardDisable.all { it })
    }

    class Controller {
        @JvmField var mEmergencyOnly = true
        @JvmField var mCarrier: Array<String?> = arrayOf("Real one", "Real two")
        @JvmField var mCustomCarrier: Array<String?> = arrayOf("Custom one", null)
        @JvmField var mCardDisable = booleanArrayOf(true, true)
        @JvmField var mSimError = booleanArrayOf(true, true)
    }
}
