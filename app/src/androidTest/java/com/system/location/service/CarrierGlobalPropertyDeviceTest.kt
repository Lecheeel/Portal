package com.system.location.service

import android.Manifest
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.telephony.SubscriptionManager
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

/** Reads property service through shell, outside target-app injected getters. */
class CarrierGlobalPropertyDeviceTest {
    @Test fun globalPropertiesMatchBothSlotsAndRestoreAcrossPerAppAndStop() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.sharedPrefs
        val saved = prefs.all
        val backend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
        fun property(key: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("getprop $key")).use { String(it.readBytes(), Charsets.UTF_8).trim() }
        val expected = mapOf("gsm.sim.operator.numeric" to "310260,50501",
            "gsm.sim.operator.alpha" to "Property US,Property AU",
            "gsm.sim.operator.iso-country" to "us,au",
            "gsm.operator.numeric" to "310260,50501",
            "gsm.operator.alpha" to "Network US,Network AU",
            "gsm.operator.iso-country" to "us,au",
            "gsm.sim.state" to "READY,READY",
            "gsm.network.type" to "NR,LTE")
        fun phonePid() = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("pidof com.android.phone")).use { String(it.readBytes(), Charsets.UTF_8).trim() }
        var originalPhonePid = phonePid()
        assertTrue("Phone process must be running", originalPhonePid.isNotEmpty())
        val original = expected.keys.associateWith(::property)
        var phoneRestarted = false
        fun restoredValues(values: Map<String, String>) = if (phoneRestarted && values == original)
            values - "gsm.network.type" else values
        fun assertStableBaseline() {
            repeat(3) {
                Thread.sleep(250)
                assertEquals("Phone process restarted during subscription notification", originalPhonePid, phonePid())
                val restored = restoredValues(original)
                assertEquals(restored, restored.keys.associateWith(::property))
                if (phoneRestarted) assertNotEquals("Modeled RATs survived withdrawal",
                    expected.getValue("gsm.network.type"), property("gsm.network.type"))
            }
        }
        fun awaitProperties(values: Map<String, String>, timeout: Long = 5000) {
            val until = android.os.SystemClock.elapsedRealtime() + timeout
            var current: Map<String, String>
            val target = restoredValues(values)
            do {
                current = target.keys.associateWith(::property)
                if (current == target) return
                Thread.sleep(150)
            } while (android.os.SystemClock.elapsedRealtime() < until)
            assertEquals(target, current)
        }
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val changed = java.util.concurrent.LinkedBlockingQueue<List<Int>>()
        lateinit var listener: SubscriptionManager.OnSubscriptionsChangedListener
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    changed.offer(subscriptions.activeSubscriptionInfoList.orEmpty().map { it.simSlotIndex }.sorted())
                }
            }
        }
        var listening = false
        fun awaitSubscriptionEvent(slots: List<Int>) {
            val until = android.os.SystemClock.elapsedRealtime() + 5000
            var last: List<Int>? = null
            do {
                changed.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { last = it }
                if (last == slots) return
            } while (android.os.SystemClock.elapsedRealtime() < until)
            fail("Subscription listener missed slots=$slots; last=$last")
        }
        var fixtureStarted = false
        try {
            assumeTrue("Requires no active physical SIM", context.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            fixtureStarted = true
            subscriptions.addOnSubscriptionsChangedListener(java.util.concurrent.Executor { it.run() }, listener)
            listening = true
            changed.clear()
            val config = CarrierIdentityConfig(sims = listOf(
                SimCarrierProfile(1, virtual = true, countryIso = "US", mcc = 310, mnc = 260,
                    carrierName = "Property US", operatorName = "Network US"),
                SimCarrierProfile(2, virtual = true, countryIso = "AU", mcc = 505, mnc = 1,
                    mncDigits = 2, carrierName = "Property AU", operatorName = "Network AU", radio = CarrierRadio.LTE)))
            check(prefs.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            assertTrue(ScenarioRuntime.start(Scenario("carrier-properties", "Carrier properties", point = Wgs84(-33.86, 151.2))).await())
            assertTrue(MockServiceHelper.putConfig(location, context))
            awaitProperties(expected)
            awaitSubscriptionEvent(listOf(0, 1))
            val restartMode = InstrumentationRegistry.getArguments().getString("restart_phone")
            if (restartMode == "true" || restartMode == "external") {
                val before = phonePid()
                assertTrue("Unexpected phone PID format", before.matches(Regex("[1-9][0-9]*")))
                if (restartMode == "external") {
                    val token = InstrumentationRegistry.getArguments().getString("restart_token").orEmpty()
                    require(token.matches(Regex("[a-zA-Z0-9-]{1,64}")))
                    java.io.File(context.cacheDir, "portal-phone-restart-$token").writeText(before)
                } else {
                    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("su 0 kill -9 $before"))
                        .use { it.readBytes() }
                }
                val until = android.os.SystemClock.elapsedRealtime() + 30_000
                var next = ""
                var serviceStatus = ""
                var ready = false
                do {
                    next = phonePid()
                    serviceStatus = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("service check phone"))
                        .use { String(it.readBytes(), Charsets.UTF_8) }
                    if (next.isNotEmpty() && next != before && serviceStatus.contains("found") && !serviceStatus.contains("not found")) {
                        ready = true
                        break
                    }
                    Thread.sleep(200)
                } while (android.os.SystemClock.elapsedRealtime() < until)
                assertTrue("Phone process/service did not restart: before=$before after=$next service=$serviceStatus", ready)
                originalPhonePid = next
                phoneRestarted = true
                assertTrue(MockServiceHelper.putConfig(location, context))
                awaitProperties(expected, 15_000)
                System.err.println("PORTAL_PHONE_RESTART_RECOVERED $before -> $next")
            }
            changed.clear()
            check(prefs.edit().putString("carrierIdentityJson",
                CarrierIdentityCodec.encode(config.copy(mode = CarrierIdentityMode.PER_APP))).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            awaitProperties(original)
            awaitSubscriptionEvent(emptyList())
            awaitProperties(original)
            assertStableBaseline()
            changed.clear()
            check(prefs.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            awaitProperties(expected)
            awaitSubscriptionEvent(listOf(0, 1))
            changed.clear()
            ScenarioRuntime.stop().await()
            awaitProperties(original)
            awaitSubscriptionEvent(emptyList())
            awaitProperties(original)
            assertStableBaseline()
        } finally {
            if (listening) subscriptions.removeOnSubscriptionsChangedListener(listener)
            try { if (fixtureStarted) {
                ScenarioRuntime.stop().await()
                val edit = prefs.edit()
                for (key in listOf("carrierIdentityEnabled", "carrierIdentityJson")) when (val value = saved[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                }
                check(edit.commit())
                MockServiceHelper.putConfig(location, context)
                ScenarioRuntime.selectBackend(backend).await()
            } } finally { automation.dropShellPermissionIdentity() }
        }
    }
}
