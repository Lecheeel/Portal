package com.system.location.service.core

import com.system.location.service.core.carrier.*
import com.system.location.service.core.environment.*
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.*
import org.junit.Test

class CarrierNrCellSourceTest {
    private val profile = SimCarrierProfile(1, countryIso = "US", mcc = 310, mnc = 260,
        carrierName = "Test NR", nrBand = 77, nrArfcn = 650000)
    private val point = Wgs84(40.7128, -74.0060)

    @Test fun sameInputIsStableAndSimSlotsHaveSeparateIdentities() {
        val first = CarrierNrCellSource.cellsAt(point, 42, profile)
        assertEquals(first, CarrierNrCellSource.cellsAt(point, 42, profile))
        assertNotEquals(first.map { it.cellId },
            CarrierNrCellSource.cellsAt(point, 42, profile.copy(slot = 2)).map { it.cellId })
        assertEquals(1, first.count { it.registered })
        assertEquals(first.size, first.map { it.pci }.distinct().size)
        assertEquals(first.size, first.map { it.cellId }.distinct().size)
        assertTrue(first.all { it.type == CellType.NR && it.operatorNumeric == "310260" &&
            it.arfcn == 650000 && it.bands == listOf(77) && it.pci!! in 0..1007 &&
            it.cellId in 1..CellTower.MAX_NR_NCI })
    }

    @Test fun lteReferencesDoNotReuseLteIdentifiersOrChannelsAsNr() {
        val refs = listOf(CellTower(CellType.LTE, 460, 0, 12, 1234,
            pci = 10, arfcn = 1200, rsrp = -82, registered = true, mncDigits = 2))
        val generated = CarrierNrCellSource.cellsAt(point, 42, profile, refs)
        assertEquals(2, generated.size)
        assertTrue(generated.all { it.type == CellType.NR && it.arfcn != refs[0].arfcn &&
            it.cellId != refs[0].cellId })
        assertEquals(-82, generated.single { it.registered }.rsrp)
    }

    @Test fun channelValidationRejectsWrongBandAndRaster() {
        assertTrue(CarrierNrChannel.valid(41, 520002))
        assertFalse(CarrierNrChannel.valid(41, 520001))
        assertFalse(CarrierNrChannel.valid(78, 680000))
        assertThrows(IllegalArgumentException::class.java) { profile.copy(nrBand = 78, nrArfcn = 680000) }
        val encoded = EnvironmentProfileCodec.encode(EnvironmentProfile(EnvironmentSourceType.PROCEDURAL,
            generatedAtMillis = 0, seed = 42, cells = CarrierNrCellSource.cellsAt(point, 42, profile)))
        assertEquals(listOf(77), EnvironmentProfileCodec.decode(encoded).cells.first().bands)
    }
}
