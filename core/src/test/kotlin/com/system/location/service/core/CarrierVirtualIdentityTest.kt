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
        assertEquals(900_000_001, CarrierVirtualIdentity.subscriptionId(1))
        assertEquals(1, CarrierVirtualIdentity.slotForSubscriptionId(900_000_001))
        assertNull(CarrierVirtualIdentity.slotForSubscriptionId(42))
        val iccid = CarrierVirtualIdentity.iccid(us)
        assertEquals(20, iccid.length)
        assertTrue(iccid.all(Char::isDigit))
        assertTrue(luhn(iccid))
        val imsi = CarrierVirtualIdentity.imsi(us)
        assertEquals(15, imsi.length)
        assertTrue(imsi.startsWith("310260"))
        assertEquals(iccid, CarrierVirtualIdentity.iccid(us))
        val carrierId = CarrierVirtualIdentity.carrierId(us)
        assertTrue(carrierId in 1_000..8_999)
        assertEquals(carrierId, CarrierVirtualIdentity.carrierId(us))
        assertNotEquals(carrierId, CarrierVirtualIdentity.carrierId(cn))
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
