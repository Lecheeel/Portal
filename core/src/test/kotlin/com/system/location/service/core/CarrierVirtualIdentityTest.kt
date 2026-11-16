package com.system.location.service.core

import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.CarrierIdentityMode
import com.system.location.service.core.carrier.CarrierNrBandCatalog
import com.system.location.service.core.carrier.CarrierNrChannel
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import org.junit.Assert.*
import org.junit.Test

class CarrierVirtualIdentityTest {
    private val cn = SimCarrierProfile(1, virtual = true, countryIso = "CN", mcc = 460, mnc = 0,
        mncDigits = 2, carrierName = "China Mobile", nrBand = 41, nrArfcn = 520002)
    private val us = SimCarrierProfile(2, virtual = true, countryIso = "US", mcc = 310, mnc = 260,
        carrierName = "T-Mobile", nrBand = 71, nrArfcn = 127000)
    private val au = SimCarrierProfile(1, virtual = true, countryIso = "AU", mcc = 505, mnc = 1,
        mncDigits = 2, carrierName = "Telstra", nrBand = 78, nrArfcn = 640000)

    @Test fun virtualIdsAreStableValidLengthsAndLuhnChecked() {
        val assigned = us.copy(virtualSubscriptionId = 47)
        assertEquals(47, CarrierVirtualIdentity.subscriptionId(assigned))
        assertEquals(2, CarrierVirtualIdentity.slotForSubscriptionId(47, listOf(assigned)))
        assertNull(CarrierVirtualIdentity.slotForSubscriptionId(900_000_002, listOf(assigned)))
        assertEquals(1, CarrierVirtualIdentity.slotForSubscriptionId(900_000_001)) // legacy recognition
        assertNull(CarrierVirtualIdentity.slotForSubscriptionId(42))
        assertThrows(IllegalArgumentException::class.java) {
            CarrierIdentityConfig(sims = listOf(assigned, assigned.copy(slot = 1)))
        }
        val iccid = CarrierVirtualIdentity.iccid(us)
        assertEquals(20, iccid.length)
        assertTrue(iccid.all(Char::isDigit))
        assertTrue(luhn(iccid))
        val imsi = CarrierVirtualIdentity.imsi(us)
        assertEquals(15, imsi.length)
        assertTrue(imsi.startsWith("310260"))
        assertEquals(iccid, CarrierVirtualIdentity.iccid(us))
        val carrierId = CarrierVirtualIdentity.carrierId(us)
        assertEquals(1, carrierId)
        assertEquals(carrierId, CarrierVirtualIdentity.carrierId(us.copy(slot = 1)))
        assertEquals(1345, CarrierVirtualIdentity.carrierId(au))
        assertEquals(1435, CarrierVirtualIdentity.carrierId(cn))
        assertEquals(-1, CarrierVirtualIdentity.carrierId(us.copy(mcc = 999, mnc = 999)))
    }

    @Test fun seedsSeparateInstallationsAndSurviveDisplayAndRadioChanges() {
        val first = us.copy(identitySeed = 123456789L)
        val second = us.copy(identitySeed = -987654321L)
        assertNotEquals(CarrierVirtualIdentity.imsi(first), CarrierVirtualIdentity.imsi(second))
        assertNotEquals(CarrierVirtualIdentity.iccid(first), CarrierVirtualIdentity.iccid(second))
        val renamed = first.copy(carrierName = "Renamed", operatorName = "Other", nrBand = 77, nrArfcn = 650000)
        assertEquals(CarrierVirtualIdentity.imsi(first), CarrierVirtualIdentity.imsi(renamed))
        assertEquals(CarrierVirtualIdentity.iccid(first), CarrierVirtualIdentity.iccid(renamed))
        val decoded = com.system.location.service.core.carrier.CarrierIdentityCodec.decode(
            com.system.location.service.core.carrier.CarrierIdentityCodec.encode(CarrierIdentityConfig(sims = listOf(first))))
        assertEquals(first, decoded.sims.single())
        assertTrue(CarrierVirtualIdentity.iccid(first).startsWith("891"))
        assertTrue(CarrierVirtualIdentity.iccid(au).startsWith("8961"))
        assertTrue(CarrierVirtualIdentity.iccid(cn).startsWith("8986"))
        for (profile in listOf(first, second, cn, au)) {
            assertTrue(luhn(CarrierVirtualIdentity.iccid(profile)))
            assertEquals(15, CarrierVirtualIdentity.imsi(profile).length)
        }
    }

    @Test fun usAustraliaAndChinaBandCatalogsUseSupportedRasters() {
        assertTrue(CarrierNrBandCatalog.commonBands("US", 310, 260).containsAll(listOf(41, 71, 77, 78)))
        assertTrue(CarrierNrBandCatalog.commonBands("AU", 505, 1).containsAll(listOf(1, 3, 28, 40, 78, 79)))
        assertTrue(CarrierNrBandCatalog.commonBands("CN", 460, 0).containsAll(listOf(41, 78, 79)))
        for (band in CarrierNrChannel.bands) {
            val arfcn = CarrierNrChannel.defaultArfcn(band)
            assertTrue("n$band default $arfcn", CarrierNrChannel.valid(band, arfcn))
        }
        assertTrue(CarrierNrChannel.valid(71, 127000))
        assertTrue(CarrierNrChannel.valid(5, 176000))
        assertTrue(CarrierNrChannel.valid(66, 430000))
        assertFalse(CarrierNrChannel.valid(71, 140000))
    }

    @Test fun virtualProfilesAreSerializableWithoutChangingPhysicalDefaults() {
        val config = CarrierIdentityConfig(mode = CarrierIdentityMode.GLOBAL, sims = listOf(us))
        val encoded = com.system.location.service.core.carrier.CarrierIdentityCodec.encode(config)
        assertTrue(encoded.contains("\"virtual\":true"))
        val decoded = com.system.location.service.core.carrier.CarrierIdentityCodec.decode(encoded)
        assertTrue(decoded.sims.single().virtual)
        assertFalse(SimCarrierProfile(2, countryIso = "US", mcc = 310, mnc = 260,
            carrierName = "x").virtual)
    }

    private fun luhn(number: String): Boolean {
        var sum = 0
        for ((index, char) in number.reversed().withIndex()) {
            var value = char.digitToInt()
            if (index % 2 == 1) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
        }
        return sum % 10 == 0
    }
}
