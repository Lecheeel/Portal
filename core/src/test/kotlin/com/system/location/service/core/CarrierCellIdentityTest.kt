package com.system.location.service.core

import com.system.location.service.core.carrier.*
import com.system.location.service.core.environment.*
import org.junit.Assert.*
import org.junit.Test

class CarrierCellIdentityTest {
    private val profile = SimCarrierProfile(1, countryIso = "US", mcc = 310, mnc = 260,
        carrierName = "Test Carrier")
    private fun cell(type: CellType, id: Long, registered: Boolean, rsrp: Int = -95) =
        CellTower(type, 460, 0, 12, id, pci = 7,
            arfcn = if (type == CellType.NR) 620000 else 1200, rsrp = rsrp,
            registered = registered, mncDigits = 2)

    @Test fun nrProfileExposesOnlyNrAndRebindsIdentityWithoutChangingRadioFields() {
        val nr = cell(CellType.NR, 50, true)
        val original = listOf(cell(CellType.LTE, 1, true), nr, cell(CellType.NR, 51, false))
        val result = CarrierCellIdentity.overlay(original, profile)
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == CellType.NR && it.operatorNumeric == "310260" })
        assertEquals(1, result.count { it.registered })
        assertEquals(nr.arfcn, result.first().arfcn)
        assertEquals(nr.cellId, result.first().cellId)
        assertEquals(nr.pci, result.first().pci)
        assertEquals("46000", nr.operatorNumeric)
    }

    @Test fun doesNotReinterpretLteFieldsAsNrWhenNoNrEvidenceExists() {
        assertTrue(CarrierCellIdentity.overlay(listOf(cell(CellType.LTE, 1, true)), profile).isEmpty())
    }

    @Test fun strongestMatchingCellIsServingWhenCaptureContainsOnlyNrNeighbors() {
        val result = CarrierCellIdentity.overlay(listOf(cell(CellType.NR, 1, false, -100),
            cell(CellType.NR, 2, false, -80)), profile)
        assertEquals(2L, result.single { it.registered }.cellId)
    }
}
