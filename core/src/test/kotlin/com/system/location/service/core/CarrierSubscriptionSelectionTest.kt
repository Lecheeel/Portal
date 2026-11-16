package com.system.location.service.core

import com.system.location.service.core.carrier.CarrierSubscriptionSelection as Selection
import org.junit.Assert.*
import org.junit.Test

class CarrierSubscriptionSelectionTest {
    private val subscriptions = mapOf(5 to 1, 18 to 2)
    @Test fun subscriptionAndPhoneIdsHaveDifferentMeaning() {
        assertEquals(2, Selection.slot("getDataNetworkTypeForSubscriber", 18, subscriptions, 5))
        assertEquals(2, Selection.slot("getSimOperatorNumericForPhone", 1, subscriptions, 5))
        assertNull(Selection.slot("getDataNetworkTypeForSubscriber", 1, subscriptions, 5))
        assertNull(Selection.slot("getSimOperatorNumericForPhone", 18, subscriptions, 5))
    }
    @Test fun defaultSentinelAndNoArgumentUseCurrentDefaultButInvalidIdDoesNot() {
        assertEquals(2, Selection.slot("getServiceStateForSubscriber", Int.MAX_VALUE, subscriptions, 18))
        assertEquals(1, Selection.slot("getAllCellInfo", null, subscriptions, 5))
        assertNull(Selection.slot("getServiceStateForSubscriber", -1, subscriptions, 18))
        assertNull(Selection.slot("getServiceStateForSubscriber", Int.MAX_VALUE, subscriptions, -1))
    }
    @Test fun absentPhysicalSlotsNeverResolveAndSubZeroIsAllowedWhenPresent() {
        assertNull(Selection.slot("getSimCountryIsoForPhone", 0, mapOf(18 to 2), 18))
        assertEquals(1, Selection.slot("getServiceStateForSubscriber", 0, mapOf(0 to 1), 0))
        assertNull(Selection.slot("getServiceStateForSubscriber", null, emptyMap(), 0))
    }
}
