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

    @Test fun internalRegistrationNeverRetainsOriginalCellIdentity() {
        val reflection = com.system.location.service.hook.runtime.Reflection
        val builder = reflection.newInstance(Class.forName("android.telephony.NetworkRegistrationInfo\$Builder"))
        reflection.callMethod(builder, "setDomain", 2)
        reflection.callMethod(builder, "setTransportType", 1)
        reflection.callMethod(builder, "setRegistrationState", 1)
        reflection.callMethod(builder, "setAccessNetworkTechnology", 13)
        reflection.callMethod(builder, "setRegisteredPlmn", "46000")
        val tower = com.system.location.service.core.environment.CellTower(
            com.system.location.service.core.environment.CellType.NR, 460, 0, 1234, 123456L,
            pci = 10, arfcn = 640000, rsrp = -90, registered = true, mncDigits = 2)
        val cell = com.system.location.service.hook.hooks.telephony.CellInfoSynthesis
            .buildNrResult(tower).getOrThrow() as android.telephony.CellInfoNr
        reflection.callMethod(builder, "setCellIdentity", cell.cellIdentity)
        val registration = reflection.callMethod(builder, "build")!!
        val original = ServiceState().apply {
            setOperatorName("Original", "Original", "46000")
        }
        reflection.callMethod(original, "addNetworkRegistrationInfo", registration)
        val copy = CarrierServiceStateHook.copyWithIdentity(original, profile)
        val results = reflection.callMethod(copy, "getNetworkRegistrationInfoList") as List<*>
        val item = results.first { reflection.callMethod(it, "getDomain") == 2 && reflection.callMethod(it, "getTransportType") == 1 }
        assertNull(reflection.callMethod(item, "getCellIdentity"))
        assertEquals("310260", reflection.callMethod(item, "getRegisteredPlmn"))
        assertEquals(20, reflection.callMethod(item, "getAccessNetworkTechnology"))
        val originals = reflection.callMethod(original, "getNetworkRegistrationInfoList") as List<*>
        val originalItem = originals.first { reflection.callMethod(it, "getDomain") == 2 && reflection.callMethod(it, "getTransportType") == 1 }
        assertNotNull(reflection.callMethod(originalItem, "getCellIdentity"))
        assertEquals("46000", reflection.callMethod(originalItem, "getRegisteredPlmn"))
        val protectedOnly = CarrierServiceStateHook.copyWithoutCellIdentity(original)
        assertEquals("46000", protectedOnly.operatorNumeric)
        val protectedRegistrations = reflection.callMethod(protectedOnly, "getNetworkRegistrationInfoList") as List<*>
        val protectedItem = protectedRegistrations.first {
            reflection.callMethod(it, "getDomain") == 2 && reflection.callMethod(it, "getTransportType") == 1
        }
        assertNull(reflection.callMethod(protectedItem, "getCellIdentity"))
        assertEquals(13, reflection.callMethod(protectedItem, "getAccessNetworkTechnology"))
        assertNotNull(reflection.callMethod(originalItem, "getCellIdentity"))
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
