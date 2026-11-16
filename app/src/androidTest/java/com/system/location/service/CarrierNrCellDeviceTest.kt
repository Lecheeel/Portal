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
    @Test fun missingAndOutOfRangeSignalMetricsFollowPlatformNormalization() {
        val source = CellTower(CellType.NR, 310, 260, 1, 123456L,
            pci = 10, arfcn = 640000, rsrp = -88, registered = true,
            mncDigits = 3, bands = listOf(78))
        for (tower in listOf(source, source.copy(rsrq = -99, rssnr = 99))) {
            val cell = CellInfoSynthesis.buildNrResult(tower).getOrThrow() as CellInfoNr
            val signal = cell.cellSignalStrength as android.telephony.CellSignalStrengthNr
            assertEquals(CellInfo.UNAVAILABLE, signal.ssRsrq)
            assertEquals(CellInfo.UNAVAILABLE, signal.ssSinr)
            assertEquals(CellInfo.UNAVAILABLE, signal.csiRsrp)
            assertEquals(-88, signal.dbm)
            assertEquals(52, signal.asuLevel)
            val reference = com.system.location.service.hook.runtime.Reflection.newInstance(
                android.telephony.CellSignalStrengthNr::class.java,
                CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                -88, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE) as android.telephony.CellSignalStrengthNr
            assertEquals(reference.level, signal.level)
        }
    }

    @Test fun aggregateContainsOnlyServingNrAndSurvivesParcel() {
        val tower = CellTower(CellType.NR, 310, 260, 1, 123456L, pci = 10, arfcn = 640000,
            rsrp = -88, rsrq = -12, rssnr = 20, registered = true, mncDigits = 3, bands = listOf(78))
        val serving = (CellInfoSynthesis.buildNrResult(tower).getOrThrow() as CellInfoNr).cellSignalStrength
            as android.telephony.CellSignalStrengthNr
        val type = Class.forName("com.system.location.service.hook.hooks.telephony.CarrierSignalStrengthHook")
        val method = type.declaredMethods.single { it.name.startsWith("aggregate") && it.parameterCount == 1 }
        method.isAccessible = true
        val aggregate = method.invoke(type.getField("INSTANCE").get(null), serving) as android.telephony.SignalStrength
        fun verify(signal: android.telephony.SignalStrength) {
            val list = signal.cellSignalStrengths
            assertEquals(1, list.size)
            assertTrue(list.single() is android.telephony.CellSignalStrengthNr)
            assertEquals(serving, list.single())
            assertEquals(serving.level, signal.level)
            assertEquals(serving.asuLevel,
                com.system.location.service.hook.runtime.Reflection.callMethod(signal, "getAsuLevel"))
            assertEquals(serving.dbm,
                com.system.location.service.hook.runtime.Reflection.callMethod(signal, "getDbm"))
        }
        verify(aggregate)
        val parcel = Parcel.obtain()
        try {
            aggregate.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            verify(android.telephony.SignalStrength.CREATOR.createFromParcel(parcel))
        } finally { parcel.recycle() }
    }

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
