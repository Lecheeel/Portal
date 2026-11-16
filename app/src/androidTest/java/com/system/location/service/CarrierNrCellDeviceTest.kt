package com.system.location.service

import android.os.Parcel
import android.telephony.CellInfo
import android.telephony.CellInfoNr
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.hook.hooks.telephony.CellInfoSynthesis
import org.junit.Assert.*
import org.junit.Test

class CarrierNrCellDeviceTest {
    @Test fun nrIdentityAndSignalSurviveAndroidConstructionAndParcel() {
        val tower = CellTower(CellType.NR, 310, 260, 0x234567, 0x123456789L,
            pci = 777, arfcn = 640000, rsrp = -88, rsrq = -12, rssnr = 20,
            registered = true, mncDigits = 3, operatorName = "Test NR", bands = listOf(78))
        val cell = CellInfoSynthesis.buildNrResult(tower).getOrThrow() as CellInfoNr
        assertNotNull("NR Android object construction failed", cell)
        fun verify(value: CellInfoNr) {
            val identity = value.cellIdentity as android.telephony.CellIdentityNr
            val signal = value.cellSignalStrength as android.telephony.CellSignalStrengthNr
            assertEquals("310", identity.mccString)
            assertEquals("260", identity.mncString)
            assertEquals(tower.cellId, identity.nci)
            assertEquals(777, identity.pci)
            assertEquals(tower.areaCode, identity.tac)
            assertEquals(640000, identity.nrarfcn)
            assertArrayEquals(intArrayOf(78), identity.bands)
            assertEquals("Test NR", identity.operatorAlphaLong)
            assertEquals(-88, signal.ssRsrp)
            assertEquals(-12, signal.ssRsrq)
            assertEquals(20, signal.ssSinr)
            assertTrue(value.isRegistered)
            assertEquals(CellInfo.CONNECTION_PRIMARY_SERVING, value.cellConnectionStatus)
        }
        verify(cell)
        val parcel = Parcel.obtain()
        try {
            cell.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            verify(CellInfo.CREATOR.createFromParcel(parcel) as CellInfoNr)
        } finally { parcel.recycle() }
    }
}
