package com.system.location.service

import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.carrier.*
import com.system.location.service.data.persistence.CarrierIdentityPreferences
import org.junit.Assert.*
import org.junit.Test

class CarrierIdentitySeedPreferencesTest {
    @Test fun legacyMigrationPersistsOnceAndIndependentPreferencesGetDifferentIdentities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "carrier-seed-fixture-${java.util.UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        try {
            val legacy = CarrierIdentityConfig(sims = listOf(
                SimCarrierProfile(1, countryIso = "US", mcc = 310, mnc = 260, carrierName = "Test"),
                SimCarrierProfile(2, countryIso = "AU", mcc = 505, mnc = 1, mncDigits = 2, carrierName = "Test AU")))
            assertTrue(preferences.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(legacy)).commit())
            val first = CarrierIdentityPreferences.migrate(preferences)!!
            assertTrue(first.sims.all { it.identitySeed != 0L })
            assertNotEquals(first.sims[0].identitySeed, first.sims[1].identitySeed)
            assertEquals(first, CarrierIdentityPreferences.migrate(preferences))
            assertEquals(first, CarrierIdentityCodec.decode(preferences.getString("carrierIdentityJson", "")!!))
            val independent = CarrierIdentityPreferences.seedMissing(legacy)
            assertNotEquals(CarrierVirtualIdentity.imsi(first.sims[0]), CarrierVirtualIdentity.imsi(independent.sims[0]))
            assertEquals(first, CarrierIdentityPreferences.seedMissing(first))
        } finally { context.deleteSharedPreferences(name) }
    }
}
