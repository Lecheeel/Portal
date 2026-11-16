package com.system.location.service.core

import com.system.location.service.core.carrier.*
import org.junit.Assert.*
import org.junit.Test

class CarrierPlmnInputTest {
    @Test fun leadingZeroMncWidthSurvivesModelSerialization() {
        for ((input, numeric) in listOf("01" to "50501", "001" to "505001")) {
            val parsed = CarrierPlmnInput.parse("505", input)
            val profile = SimCarrierProfile(1, countryIso = "AU", mcc = parsed.mcc,
                mnc = parsed.mnc, mncDigits = parsed.mncDigits, carrierName = "Test")
            val received = CarrierIdentityCodec.decode(CarrierIdentityCodec.encode(CarrierIdentityConfig(sims = listOf(profile))))
                .sims.single()
            assertEquals(numeric, received.operatorNumeric)
            assertEquals(input, received.mnc.toString().padStart(received.mncDigits, '0'))
        }
    }
    @Test fun incompleteAndNonAsciiInputsNeverSilentlyNormalize() {
        for ((mcc, mnc) in listOf("50" to "01", "000" to "01", "505" to "1",
            "505" to "0001", "５０５" to "01", "505" to "٠١", " 505" to "01")) {
            assertThrows("$mcc/$mnc", IllegalArgumentException::class.java) { CarrierPlmnInput.parse(mcc, mnc) }
        }
        assertEquals(0, CarrierPlmnInput.parse("460", "00").mnc)
    }
}
