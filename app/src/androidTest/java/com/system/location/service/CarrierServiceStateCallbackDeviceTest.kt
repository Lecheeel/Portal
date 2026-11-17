package com.system.location.service

import android.Manifest
import android.location.LocationManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.carrier.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual application callback transports in system_server; no reflective invocation of callbacks. */
class CarrierServiceStateCallbackDeviceTest {
    private class Listener : TelephonyCallback(), TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener {
        val displays = LinkedBlockingQueue<android.telephony.TelephonyDisplayInfo>()
        override fun onDisplayInfoChanged(info: android.telephony.TelephonyDisplayInfo) { displays.offer(info) }
        val states = LinkedBlockingQueue<ServiceState>()
        override fun onServiceStateChanged(serviceState: ServiceState) { states.offer(serviceState) }
    }

    @Test fun separateSubscriptionsReceiveTheirOwnPlmnAndStopAfterUnregister() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.sharedPrefs
        val saved = preferences.all
        val backend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        val registered = mutableListOf<Pair<TelephonyManager, Listener>>()
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        try {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            assumeTrue("Requires no active physical SIM", subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            val config = CarrierIdentityConfig(sims = listOf(
                SimCarrierProfile(1, virtual = true, countryIso = "US", mcc = 310, mnc = 260,
                    carrierName = "Callback US", nrBand = 71, nrArfcn = 127000),
                SimCarrierProfile(2, virtual = true, countryIso = "AU", mcc = 505, mnc = 1,
                    mncDigits = 2, carrierName = "Callback AU", radio = CarrierRadio.LTE)))
            check(preferences.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            val started = ScenarioRuntime.start(Scenario(id = "carrier-callback", name = "Carrier callback",
                point = Wgs84(-33.8688, 151.2093))).await()
            assertTrue("Scenario did not start: ${ScenarioRuntime.state.value}", started)
            assertTrue(MockServiceHelper.putConfig(location, context))
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (subscriptions.activeSubscriptionInfoList.orEmpty().size != 2 &&
                android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            val active = subscriptions.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
            assertEquals(2, active.size)
            active.forEach { info ->
                val manager = context.getSystemService(TelephonyManager::class.java)
                    .createForSubscriptionId(info.subscriptionId)
                val listener = Listener()
                manager.registerTelephonyCallback({ it.run() }, listener)
                registered.add(manager to listener)
            }
            registered.forEachIndexed { index, (_, listener) ->
                val expected = if (index == 0) "310260" else "50501"
                val timeout = android.os.SystemClock.elapsedRealtime() + 5000
                var matching: ServiceState? = null
                do {
                    val state = listener.states.poll(200, TimeUnit.MILLISECONDS)
                    assertFalse("SIM callback crossed subscriptions before initial state",
                        state?.operatorNumeric == if (index == 0) "50501" else "310260")
                    if (state?.operatorNumeric == expected) matching = state
                } while (matching == null && android.os.SystemClock.elapsedRealtime() < timeout)
                assertNotNull("SIM ${index + 1} must receive its own virtual state", matching)
                assertEquals(ServiceState.STATE_IN_SERVICE, matching!!.state)
                assertFalse(matching!!.roaming)
                val remaining = listener.states.toList()
                assertTrue("SIM callback crossed subscriptions", remaining.none {
                    it.operatorNumeric == if (index == 0) "50501" else "310260"
                })
            }
            registered.forEachIndexed { index, (_, listener) ->
                val expectedRat = if (index == 0) TelephonyManager.NETWORK_TYPE_NR else TelephonyManager.NETWORK_TYPE_LTE
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                var display: android.telephony.TelephonyDisplayInfo? = null
                do {
                    val next = listener.displays.poll(200, TimeUnit.MILLISECONDS)
                    if (next?.networkType == expectedRat) display = next
                } while (display == null && android.os.SystemClock.elapsedRealtime() < deadline)
                assertNotNull("SIM ${index + 1} did not receive its display RAT", display)
                assertEquals(android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, display!!.overrideNetworkType)
            }
            fun awaitState(listener: Listener, label: String, predicate: (ServiceState) -> Boolean) {
                val until = android.os.SystemClock.elapsedRealtime() + 5000
                do {
                    val value = listener.states.poll(200, TimeUnit.MILLISECONDS)
                    if (value != null && predicate(value)) return
                } while (android.os.SystemClock.elapsedRealtime() < until)
                fail(label)
            }
            registered.forEach { it.second.states.clear() }
            val persisted = CarrierIdentityCodec.decode(preferences.getString("carrierIdentityJson", "")!!)
            val renamed = persisted.copy(sims = persisted.sims.map { it.copy(operatorName = "Updated ${it.slot}") })
            check(preferences.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(renamed)).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            registered.forEachIndexed { index, (_, listener) ->
                awaitState(listener, "Active SIM ${index + 1} did not receive profile update") {
                    it.operatorAlphaLong == "Updated ${index + 1}" &&
                        it.operatorNumeric == if (index == 0) "310260" else "50501"
                }
            }
            registered.forEach { it.second.states.clear(); it.second.displays.clear() }
            check(preferences.edit().putBoolean("carrierIdentityEnabled", false).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            registered.forEach { (_, listener) ->
                awaitState(listener, "Disabling left active listener with virtual state") {
                    it.operatorNumeric !in listOf("310260", "50501")
                }
                val until = android.os.SystemClock.elapsedRealtime() + 5000
                var cleared = false
                do {
                    if (listener.displays.poll(200, TimeUnit.MILLISECONDS)?.networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN)
                        cleared = true
                } while (!cleared && android.os.SystemClock.elapsedRealtime() < until)
                assertTrue("Disabling retained virtual display RAT", cleared)
            }
            registered.forEach { it.second.states.clear() }
            check(preferences.edit().putBoolean("carrierIdentityEnabled", true).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            registered.forEachIndexed { index, (_, listener) ->
                awaitState(listener, "Re-enable did not notify active SIM") { it.operatorAlphaLong == "Updated ${index + 1}" }
            }
            registered.forEach { it.second.states.clear() }
            ScenarioRuntime.stop().await()
            registered.forEach { (_, listener) ->
                awaitState(listener, "Stopping left active listener with virtual state") {
                    it.operatorNumeric !in listOf("310260", "50501")
                }
            }
            registered.forEach { (manager, listener) -> manager.unregisterTelephonyCallback(listener) }
            registered.forEach { it.second.states.clear() }
            assertTrue(MockServiceHelper.putConfig(location, context))
            registered.forEach { (_, listener) ->
                assertNull("Unregistered listener received a callback", listener.states.poll(600, TimeUnit.MILLISECONDS))
            }
            registered.clear()
        } finally {
            try {
                registered.forEach { (manager, listener) -> runCatching { manager.unregisterTelephonyCallback(listener) } }
                ScenarioRuntime.stop().await()
                val edit = preferences.edit()
                for (key in listOf("carrierIdentityEnabled", "carrierIdentityJson")) when (val value = saved[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                }
                check(edit.commit())
                MockServiceHelper.putConfig(location, context)
                ScenarioRuntime.selectBackend(backend).await()
            } finally { automation.dropShellPermissionIdentity() }
        }
    }
}
