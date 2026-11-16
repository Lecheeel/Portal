package com.system.location.service

import android.Manifest
import android.location.LocationManager
import android.telephony.SubscriptionManager
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs on a real no-SIM phone: virtual subscription must be visible without a physical SIM. */
class CarrierVirtualNoSimDeviceTest {
    @Test fun globalVirtualSubscriptionIsVisibleWithoutPhysicalSim() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
        val preferences = context.sharedPrefs
        val originalPreferences = preferences.all
        val keys = listOf("carrierIdentityEnabled", "carrierIdentityJson")
        val previousBackend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        try {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            assumeTrue("Requires no active physical SIM fixture",
                subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue("Requires Xposed backend", ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            val profile = SimCarrierProfile(1, virtual = true, countryIso = "US", mcc = 310,
                mnc = 260, carrierName = "T-Mobile", operatorName = "T-Mobile USA",
                nrBand = 71, nrArfcn = 127000)
            val config = CarrierIdentityConfig(sims = listOf(profile))
            assertTrue(preferences.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", Json.encodeToString(CarrierIdentityConfig.serializer(), config))
                .commit())
            assertTrue(ScenarioRuntime.start(Scenario(
                id = "carrier-virtual-no-sim", name = "Carrier virtual no SIM",
                point = Wgs84(40.7128, -74.0060))).await())
            assertTrue(MockServiceHelper.putConfig(location, context))
            Thread.sleep(2_000)

            val active = subscriptions.activeSubscriptionInfoList.orEmpty()
            assertEquals("virtual subscription count", 1, active.size)
            val info = active.single()
            assertEquals(0, info.simSlotIndex)
            assertEquals(CarrierVirtualIdentity.subscriptionId(1), info.subscriptionId)
            assertEquals("T-Mobile", info.carrierName.toString())
            assertEquals("310", info.mccString)
            assertEquals("260", info.mncString)
            assertEquals(CarrierVirtualIdentity.iccid(profile), info.iccId)

            val snapshot = android.os.Bundle().apply {
                putString("kind", "phone")
                putInt("id", 0)
            }
            val commandAccepted = location.sendExtraCommand(
                "fused_ext",
                com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.COMMAND,
                snapshot,
            )
            assertTrue("snapshot command rejected", commandAccepted)
            assertTrue("phone runtime did not resolve virtual profile: $snapshot",
                snapshot.containsKey(
                    com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.PROFILE))
            // Carrier getters in an arbitrary app require the module's target-app scope. The
            // phone/system stack path is exercised by the profile snapshot and SystemUI fixture.
            System.err.println("PORTAL_VIRTUAL_SIM_READY ${info.subscriptionId}")
            Thread.sleep(8_000)
        } finally {
            try {
                ScenarioRuntime.stop().await()
                val edit = preferences.edit()
                keys.forEach { key -> when (val value = originalPreferences[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                } }
                check(edit.commit())
                MockServiceHelper.putConfig(location, context)
                ScenarioRuntime.selectBackend(previousBackend).await()
            } finally { automation.dropShellPermissionIdentity() }
        }
    }

    @Test fun globalVirtualSubscriptionsForBothSlots() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
        val preferences = context.sharedPrefs
        val originalPreferences = preferences.all
        val keys = listOf("carrierIdentityEnabled", "carrierIdentityJson")
        val previousBackend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        try {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            assumeTrue("Requires no active physical SIM fixture",
                subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue("Requires Xposed backend", ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            val us = SimCarrierProfile(1, virtual = true, countryIso = "US", mcc = 310,
                mnc = 260, carrierName = "T-Mobile", operatorName = "T-Mobile USA",
                nrBand = 71, nrArfcn = 127000)
            val au = SimCarrierProfile(2, virtual = true, countryIso = "AU", mcc = 505,
                mnc = 1, mncDigits = 2, carrierName = "Telstra", operatorName = "Telstra",
                nrBand = 78, nrArfcn = 640000)
            val config = CarrierIdentityConfig(sims = listOf(us, au))
            assertTrue(preferences.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", Json.encodeToString(CarrierIdentityConfig.serializer(), config))
                .commit())
            assertTrue(ScenarioRuntime.start(Scenario(
                id = "carrier-virtual-dual-slot", name = "Carrier virtual dual slot",
                point = Wgs84(-33.8688, 151.2093))).await())
            assertTrue(MockServiceHelper.putConfig(location, context))
            Thread.sleep(2_000)

            val active = subscriptions.activeSubscriptionInfoList.orEmpty()
            assertEquals("virtual subscription count", 2, active.size)
            val first = active.first { it.simSlotIndex == 0 }
            val second = active.first { it.simSlotIndex == 1 }
            assertEquals(CarrierVirtualIdentity.subscriptionId(1), first.subscriptionId)
            assertEquals(CarrierVirtualIdentity.subscriptionId(2), second.subscriptionId)
            assertEquals("T-Mobile", first.carrierName.toString())
            assertEquals("Telstra", second.carrierName.toString())
            assertEquals("310", first.mccString)
            assertEquals("260", first.mncString)
            assertEquals("505", second.mccString)
            assertEquals("01", second.mncString)
            assertEquals(CarrierVirtualIdentity.iccid(us), first.iccId)
            assertEquals(CarrierVirtualIdentity.iccid(au), second.iccId)
            System.err.println("PORTAL_VIRTUAL_DUAL_SLOT_READY ${first.subscriptionId} ${second.subscriptionId}")
        } finally {
            try {
                ScenarioRuntime.stop().await()
                val edit = preferences.edit()
                keys.forEach { key -> when (val value = originalPreferences[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                } }
                check(edit.commit())
                MockServiceHelper.putConfig(location, context)
                ScenarioRuntime.selectBackend(previousBackend).await()
            } finally { automation.dropShellPermissionIdentity() }
        }
    }
}
