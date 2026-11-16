package com.system.location.service

import android.Manifest
import android.location.LocationManager
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Host fixture: activated Xposed/system scope and two empty physical SIM slots. */
class CarrierNoSimDeviceTest {
    @Test fun globalProfilesDoNotInventSubscriptionsAndReportNoActiveSim() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        grantUiPermissions()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
        val preferences = context.sharedPrefs
        val keys = listOf("carrierIdentityEnabled", "carrierIdentityJson")
        val originalPreferences = preferences.all
        val saved = keys.associateWith { originalPreferences[it] }
        val previousBackend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        try {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            assumeTrue("Requires no active SIM fixture", subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue("Requires active Xposed backend", ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            val config = CarrierIdentityConfig(sims = listOf(
                SimCarrierProfile(1, countryIso = "US", mcc = 310, mnc = 260, carrierName = "SIM1 Test"),
                SimCarrierProfile(2, countryIso = "CN", mcc = 460, mnc = 0, mncDigits = 2, carrierName = "SIM2 Test"),
            ))
            assertTrue(preferences.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", Json.encodeToString(CarrierIdentityConfig.serializer(), config)).commit())
            val started = ScenarioRuntime.start(Scenario(
                id = "carrier-no-sim", name = "Carrier no SIM", point = Wgs84(40.7128, -74.0060))).await()
            assertTrue("Scenario must start: ${ScenarioRuntime.state.value}", started)
            assertTrue(MockServiceHelper.putConfig(location, context))
            assertEquals("no_active_sim", MockServiceHelper.carrierIdentityStatus)
            assertTrue(subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            val telephony = context.getSystemService(TelephonyManager::class.java)
            for (slot in 0..1) {
                assertNotEquals(TelephonyManager.SIM_STATE_READY, telephony.getSimState(slot))
                val reply = Bundle().apply {
                    putString("kind", "phone"); putInt("id", slot)
                    putString(CarrierIdentitySnapshotCommand.PROFILE, "must be removed")
                }
                location.sendExtraCommand("fused_ext", CarrierIdentitySnapshotCommand.COMMAND, reply)
                assertFalse("Empty slot received a synthetic identity", reply.containsKey(CarrierIdentitySnapshotCommand.PROFILE))
            }
        } finally {
            try {
                ScenarioRuntime.stop().await()
                val edit = preferences.edit()
                keys.forEach { key -> when (val value = saved[key]) {
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
