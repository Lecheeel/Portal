package com.system.location.service

import android.os.Parcel
import android.telephony.ServiceState
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.hooks.telephony.CarrierServiceStateHook
import org.junit.Assert.*
import org.junit.Test

/** Device-side Android object test, independent of module activation and physical SIM presence. */
class CarrierServiceStateDeviceTest {
    private val profile = SimCarrierProfile(1, countryIso = "US", mcc = 310, mnc = 260,
        carrierName = "Test Carrier", operatorName = "Test Network")

    @Test fun identityCopyPreservesRegistrationRoamingAndOriginalObject() {
        val original = ServiceState().apply {
            state = ServiceState.STATE_OUT_OF_SERVICE
            setOperatorName("Original Long", "Original Short", "46000")
            roaming = true
        }
        val copy = CarrierServiceStateHook.copyWithIdentity(original, profile)
        assertNotSame(original, copy)
        assertEquals("Test Network", copy.operatorAlphaLong)
        assertEquals("Test Carrier", copy.operatorAlphaShort)
        assertEquals("310260", copy.operatorNumeric)
        assertEquals(original.state, copy.state)
        assertEquals(original.roaming, copy.roaming)
        assertEquals("Original Long", original.operatorAlphaLong)
        assertEquals("46000", original.operatorNumeric)
        val parcel = Parcel.obtain()
        try {
            copy.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val received = ServiceState.CREATOR.createFromParcel(parcel)
            assertEquals("310260", received.operatorNumeric)
            assertEquals(copy.state, received.state)
            assertEquals(copy.roaming, received.roaming)
        } finally { parcel.recycle() }
    }

    @Test fun unavailableOrRedactedOperatorFieldsStayUnavailable() {
        val original = ServiceState().apply {
            state = ServiceState.STATE_POWER_OFF
            setOperatorName(null, "", null)
        }
        val copy = CarrierServiceStateHook.copyWithIdentity(original, profile)
        assertNull(copy.operatorAlphaLong)
        assertNull(copy.operatorNumeric)
        assertEquals("", copy.operatorAlphaShort)
        assertEquals(ServiceState.STATE_POWER_OFF, copy.state)
    }
}
