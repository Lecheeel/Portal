package com.system.location.service.core

import com.system.location.service.core.carrier.CarrierAppRoute
import com.system.location.service.core.carrier.CarrierCountries
import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.CarrierIdentityMode
import com.system.location.service.core.carrier.CarrierPresetCatalog
import com.system.location.service.core.carrier.CarrierRadio
import com.system.location.service.core.carrier.CarrierTarget
import com.system.location.service.core.carrier.SimCarrierProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CarrierIdentityTest {
    private val chinaMobile = SimCarrierProfile(
        slot = 1, countryIso = "CN", mcc = 460, mnc = 0, mncDigits = 2,
        carrierName = "中国移动", operatorName = "China Mobile",
    )
    private val tMobile = SimCarrierProfile(
        slot = 2, countryIso = "US", mcc = 310, mnc = 260, mncDigits = 3,
        carrierName = "T-Mobile", operatorName = "T-Mobile USA", radio = CarrierRadio.NR,
    )

    @Test fun globalIsDefaultAndDoesNotTearDownSystemCallers() {
        val config = CarrierIdentityConfig(sims = listOf(chinaMobile, tMobile))
        assertEquals(CarrierIdentityMode.GLOBAL, config.mode)
        assertEquals(CarrierTarget.INHERIT, config.targetFor("com.example.app", isSystemApp = false))
        assertEquals(CarrierTarget.INHERIT, config.targetFor("android", isSystemApp = true))
        assertEquals("46000", chinaMobile.operatorNumeric)
        assertEquals("310260", tMobile.operatorNumeric)
        assertNotNull(config.profileForSlot(1))
        assertNotNull(config.profileForSlot(2))
    }

    @Test fun perAppModeLeavesUnlistedAndSystemAppsReal() {
        val config = CarrierIdentityConfig(
            mode = CarrierIdentityMode.PER_APP,
            sims = listOf(chinaMobile, tMobile),
            appRoutes = listOf(
                CarrierAppRoute("com.example.one", CarrierTarget.SIM2),
                CarrierAppRoute("com.example.two", CarrierTarget.REAL),
            ),
        )
        assertEquals(CarrierTarget.SIM2, config.targetFor("com.example.one", false))
        assertEquals(CarrierTarget.REAL, config.targetFor("com.example.two", false))
        assertEquals(CarrierTarget.REAL, config.targetFor("com.example.three", false))
        assertEquals(CarrierTarget.REAL, config.targetFor("com.example.one", isSystemApp = true))
        assertEquals(tMobile, config.profileForTarget(CarrierTarget.SIM2))
    }

    @Test fun serializationRoundTripsDualSimAndRoutes() {
        val original = CarrierIdentityConfig(
            mode = CarrierIdentityMode.PER_APP,
            sims = listOf(chinaMobile, tMobile),
            appRoutes = listOf(CarrierAppRoute("com.example.one", CarrierTarget.SIM1)),
        )
        val json = Json.encodeToString(CarrierIdentityConfig.serializer(), original)
        assertEquals(original, Json.decodeFromString(CarrierIdentityConfig.serializer(), json))
    }

    @Test fun rejectsInvalidProfilesAndDuplicateRoutes() {
        assertThrows(IllegalArgumentException::class.java) {
            chinaMobile.copy(mcc = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            chinaMobile.copy(countryIso = "cn")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarrierIdentityConfig(
                sims = listOf(chinaMobile, chinaMobile.copy(mnc = 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarrierIdentityConfig(
                appRoutes = listOf(
                    CarrierAppRoute("com.example.one"),
                    CarrierAppRoute("com.example.one"),
                ),
            )
        }
    }

    @Test fun allIsoCountriesAreSelectableAndKnownPresetsResolve() {
        assertTrue(CarrierCountries.all.any { it.iso == "CN" })
        assertTrue(CarrierCountries.all.any { it.iso == "US" })
        assertTrue(CarrierCountries.all.size > 200)
        assertTrue(CarrierCountries.isValid("us"))
        assertFalse(CarrierCountries.isValid("ZZ"))
        assertNull(CarrierCountries.defaultMcc("ZZ"))
        assertTrue(CarrierPresetCatalog.forCountry("US").any { it.carrierName == "T-Mobile" })
    }

    @Test fun disabledProfilesAreNotResolvedButRemainSerializable() {
        val config = CarrierIdentityConfig(sims = listOf(chinaMobile.copy(enabled = false)))
        assertNull(config.profileForSlot(1))
        assertTrue(config.sims.single().enabled.not())
    }
}
