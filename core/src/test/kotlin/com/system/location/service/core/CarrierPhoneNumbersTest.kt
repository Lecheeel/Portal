package com.system.location.service.core

import com.system.location.service.core.carrier.*
import org.junit.Assert.*
import org.junit.Test

class CarrierPhoneNumbersTest {
    @Test fun cityNumbersRespectSevenDigitCityAndOriginalOperatorAllocation() {
        assertTrue(CarrierPhoneNumbers.cities(null).map { it.name }.distinct().size >= 300)
        for (operator in listOf("CMCC", "CUCC", "CTCC")) {
            for (city in CarrierPhoneNumbers.cities(operator)) {
                val number = CarrierPhoneNumbers.generate("CN", city.name, operator)
                assertTrue("$city $number", CarrierPhoneNumbers.belongsToCity(number, city))
                assertTrue(number, CarrierPhoneNumbers.matchesRegion(number, "CN"))
            }
        }
        val numbers = (1..24).map { CarrierPhoneNumbers.generate("CN", "北京市", "CMCC") }.toSet()
        assertTrue(numbers.size > 1)
        assertThrows(IllegalArgumentException::class.java) { CarrierPhoneNumbers.generate("CN", "北京市") }
        assertThrows(IllegalStateException::class.java) { CarrierPhoneNumbers.generate("CN", "不存在城市", "CMCC") }
    }
    @Test fun everyAvailableInternationalRegionGeneratesMatchingNumbers() {
        assertTrue(CarrierPhoneNumbers.regions.size >= 240)
        CarrierPhoneNumbers.regions.filter { it.iso != "CN" }.forEach { region ->
            repeat(4) {
                val number = CarrierPhoneNumbers.generate(region.iso)
                assertTrue("${region.iso}: $number", CarrierPhoneNumbers.matchesRegion(number, region.iso))
                assertEquals(number, CarrierPhoneNumbers.normalize(number))
            }
        }
    }
    @Test fun phoneNumberPersistsAndLegacyConfigDefaultsToEmpty() {
        val old = CarrierIdentityCodec.decode("""{"sims":[{"slot":1,"countryIso":"US","mcc":310,"mnc":260,"carrierName":"Test"}]}""")
        assertEquals("", old.sims.single().phoneNumber)
        val updated = old.copy(sims = listOf(old.sims.single().copy(phoneNumber = "+12025550123")))
        assertEquals(updated, CarrierIdentityCodec.decode(CarrierIdentityCodec.encode(updated)))
        assertEquals("+12025550123", CarrierPhoneNumbers.normalize("+1 (202) 555-0123"))
        assertThrows(IllegalArgumentException::class.java) { CarrierPhoneNumbers.normalize("１３８００１３８０００") }
        assertThrows(IllegalArgumentException::class.java) { updated.sims.single().copy(phoneNumber = "abc") }
    }
}
