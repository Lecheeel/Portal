package com.system.location.service.hook.hooks.telephony

import android.telephony.TelephonyManager
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.CdmaTower
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelephonyTargetIdentityTest {
    @Test
    fun registeredCellSuppliesOnlyModeledTargetIdentity() {
        val profile = profile().copy(cells = listOf(
            CellTower(
                type = CellType.NR,
                mcc = 310,
                mnc = 1,
                areaCode = 10,
                cellId = 20,
                rsrp = -90,
                registered = true,
                mncDigits = 3,
                operatorName = "Example Carrier",
            ),
        ))

        val identity = telephonyTargetIdentity(profile)

        assertEquals("310001", identity?.operatorNumeric)
        assertEquals("Example Carrier", identity?.operatorName)
        assertEquals(TelephonyManager.PHONE_TYPE_GSM, identity?.phoneType)
        assertEquals(TelephonyManager.NETWORK_TYPE_NR, identity?.networkType)
    }

    @Test
    fun missingOrLegacyOperatorFieldsStayUnknownWithoutDeviceFallback() {
        val legacy = profile().copy(cells = listOf(
            CellTower(CellType.LTE, 460, 1, 10, 20, rsrp = -90, registered = true),
        ))

        assertNull(telephonyTargetIdentity(null))
        assertNull(telephonyTargetIdentity(profile()))
        val identity = telephonyTargetIdentity(legacy)
        assertNull(identity?.operatorNumeric)
        assertNull(identity?.operatorName)
        assertEquals(TelephonyManager.NETWORK_TYPE_LTE, identity?.networkType)
    }

    @Test
    fun registeredCdmaCellSelectsCdmaPhoneAndNetworkType() {
        val profile = profile().copy(cdmaCells = listOf(CdmaTower(1, 2, 3, cdmaDbm = -90, registered = true)))

        val identity = telephonyTargetIdentity(profile)

        assertEquals(TelephonyManager.PHONE_TYPE_CDMA, identity?.phoneType)
        assertEquals(TelephonyManager.NETWORK_TYPE_CDMA, identity?.networkType)
        assertNull(identity?.operatorNumeric)
        assertNull(identity?.operatorName)
    }

    @Test
    fun subServiceReplyUsesModeledIdentityOrUnknownValues() {
        val identity = telephonyTargetIdentity(profile().copy(cells = listOf(
            CellTower(
                type = CellType.LTE,
                mcc = 310,
                mnc = 1,
                areaCode = 10,
                cellId = 20,
                rsrp = -90,
                registered = true,
                mncDigits = 2,
            ),
        )))

        assertEquals(-1, telephonySubReply(TelephonySubOperation.PHONE_ID, 1, identity))
        assertEquals(false, telephonySubReply(TelephonySubOperation.ACTIVE_SUBSCRIPTION, 1, identity))
        assertEquals(
            TelephonyManager.SIM_STATE_READY,
            telephonySubReply(TelephonySubOperation.SIM_STATE_FOR_SLOT, 0, identity),
        )
        assertEquals(
            TelephonyManager.SIM_STATE_UNKNOWN,
            telephonySubReply(TelephonySubOperation.SIM_STATE_FOR_SUBSCRIPTION, 1, identity),
        )
        assertEquals("", telephonySubReply(TelephonySubOperation.NETWORK_COUNTRY_ISO, 0, identity))
        assertEquals(-1, telephonySubReply(TelephonySubOperation.PHONE_ID, 0, null))
        assertEquals(false, telephonySubReply(TelephonySubOperation.ACTIVE_SUBSCRIPTION, 0, null))
        assertEquals(
            TelephonyManager.SIM_STATE_UNKNOWN,
            telephonySubReply(TelephonySubOperation.SIM_STATE_FOR_SLOT, 0, null),
        )
    }

    private fun profile() = EnvironmentProfile(
        source = EnvironmentSourceType.REPLAY,
        generatedAtMillis = 1,
        seed = 1,
    )
}
