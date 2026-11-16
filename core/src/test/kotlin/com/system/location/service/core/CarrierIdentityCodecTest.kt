package com.system.location.service.core

import com.system.location.service.core.carrier.*
import com.system.location.service.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class CarrierIdentityCodecTest {
    @Test fun encodedProfilesUseTheSameDecoderInRuntimeConfiguration() {
        val profile = SimCarrierProfile(2, countryIso = "CN", mcc = 460, mnc = 0,
            mncDigits = 2, carrierName = "测试运营商", nrBand = 41, nrArfcn = 520002)
        val config = CarrierIdentityConfig(sims = listOf(profile), mode = CarrierIdentityMode.PER_APP,
            appRoutes = listOf(CarrierAppRoute("example.app", CarrierTarget.SIM2)))
        val encoded = CarrierIdentityCodec.encode(config)
        assertEquals(config, CarrierIdentityCodec.decode(encoded))
        val updated = PreparedRuntimeConfiguration.prepare(mapOf("carrier_identity_json" to encoded))
            .applyTo(RuntimeSnapshot())
        assertEquals(config, updated.configuration.carrierIdentity)
    }

    @Test fun tooManyLongPackageRulesAreRejectedBeforePersistenceEvenUnderCountLimit() {
        val routes = (0 until 400).map { CarrierAppRoute("example.app.$it." + "a".repeat(220)) }
        val config = CarrierIdentityConfig(appRoutes = routes)
        assertThrows(IllegalArgumentException::class.java) { CarrierIdentityCodec.encode(config) }
        assertThrows(IllegalArgumentException::class.java) {
            CarrierIdentityCodec.decode(" ".repeat(CarrierIdentityCodec.MAX_LENGTH + 1))
        }
    }

    @Test fun blankAndInvalidProfilesNeverSilentlyBecomeEmptyConfiguration() {
        for (value in listOf("", " ", "{broken", "{\"sims\":[{\"slot\":3}]}")) {
            assertThrows(IllegalArgumentException::class.java) { CarrierIdentityCodec.decode(value) }
        }
        assertEquals(CarrierIdentityConfig(), CarrierIdentityCodec.decode("{}"))
    }
}
