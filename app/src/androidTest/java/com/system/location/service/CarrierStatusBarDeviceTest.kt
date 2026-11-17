package com.system.location.service

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.view.accessibility.AccessibilityNodeInfo
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

/** Actual SystemUI text through accessibility; requires explicitly enabled SystemUI module scope. */
class CarrierStatusBarDeviceTest {
    @Test fun globalDualCarrierTextAppearsAndDisablingRemovesIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val preferences = context.sharedPrefs
        val saved = preferences.all
        val previousBackend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        val originalServiceInfo = automation.serviceInfo
        val originalFlags = originalServiceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).use { it.readBytes() }
        fun texts(): List<String> {
            val result = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.let(result::add)
                node.contentDescription?.toString()?.let(result::add)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            automation.windows.forEach { visit(it.root) }
            return result
        }
        fun capture(label: String) {
            if (InstrumentationRegistry.getArguments().getString("capture_systemui") != "true") return
            fun dump() = String(shell("dumpsys activity service com.android.systemui/.SystemUIService NetworkController"), Charsets.UTF_8)
            var data = dump()
            if (label == "global") {
                val profiles = CarrierIdentityCodec.decode(preferences.getString("carrierIdentityJson", "")!!).sims
                val telephony = context.getSystemService(android.telephony.TelephonyManager::class.java)
                val expected = profiles.associate { profile -> profile.virtualSubscriptionId to
                    telephony.createForSubscriptionId(profile.virtualSubscriptionId).signalStrength!!.level }
                fun matches(): Boolean = expected.all { (id, level) ->
                    val block = data.substringAfter("- NetworkController.MobileSignalController($id) -----", "")
                        .substringBefore("\n      - NetworkController.")
                    level > 0 && block.lineSequence().firstOrNull { it.trim().startsWith("Current State:") }
                        ?.contains("level=$level,") == true
                }
                val until = SystemClock.elapsedRealtime() + 5000
                while (!matches() && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(200)
                    data = dump()
                }
                java.io.File(context.cacheDir, "carrier-systemui-$label.txt").writeText(data)
                assertTrue("Both SystemUI controller levels must match serving NR: $expected", matches())
            } else java.io.File(context.cacheDir, "carrier-systemui-$label.txt").writeText(data)
            java.io.File(context.cacheDir, "carrier-systemui-$label-accessibility.txt")
                .writeText(texts().joinToString("\n"))
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "carrier-systemui-$label.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
        fun awaitText(present: Boolean): Boolean {
            shell("cmd statusbar expand-settings")
            val deadline = SystemClock.elapsedRealtime() + 15_000
            do {
                val visible = texts().joinToString("\n")
                val first = visible.contains("Portal Alpha")
                val second = visible.contains("Portal Beta")
                if (if (present) first && second else !first && !second) return true
                Thread.sleep(500)
            } while (SystemClock.elapsedRealtime() < deadline)
            capture("text-timeout")
            System.err.println("PORTAL_CARRIER_TEXT_TIMEOUT present=$present visible=${texts().joinToString("|")}")
            return false
        }
        var fixtureStarted = false
        try {
            assumeTrue("Requires no physical SIM", context.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            fixtureStarted = true
            val config = CarrierIdentityConfig(sims = listOf(
                SimCarrierProfile(1, virtual = true, countryIso = "US", mcc = 310, mnc = 260,
                    carrierName = "Portal Alpha", nrBand = 71, nrArfcn = 127000),
                SimCarrierProfile(2, virtual = true, countryIso = "AU", mcc = 505, mnc = 1,
                    mncDigits = 2, carrierName = "Portal Beta")))
            assertTrue(preferences.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            assertTrue(ScenarioRuntime.start(Scenario(id = "carrier-statusbar", name = "Carrier statusbar",
                point = Wgs84(-33.8688, 151.2093))).await())
            assertTrue(MockServiceHelper.putConfig(location, context))
            shell("cmd statusbar expand-settings")
            assertTrue("Both simulated carrier names must be visible in SystemUI", awaitText(true))
            capture("global")
            assertTrue(preferences.edit().putString("carrierIdentityJson",
                CarrierIdentityCodec.encode(config.copy(mode = CarrierIdentityMode.PER_APP))).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            assertTrue("PER_APP must leave SystemUI real", awaitText(false))
            assertTrue(preferences.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            assertTrue("Returning to GLOBAL must restore both names", awaitText(true))
            assertTrue(preferences.edit().putBoolean("carrierIdentityEnabled", false).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            assertTrue("Disabling must remove both simulated names", awaitText(false))
            assertTrue(preferences.edit().putBoolean("carrierIdentityEnabled", true).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            assertTrue("Re-enabling must restore both names", awaitText(true))
            ScenarioRuntime.stop().await()
            assertTrue("Stopping the scenario must remove simulated names", awaitText(false))
            capture("stopped")
        } finally {
            try { if (fixtureStarted) {
                shell("cmd statusbar collapse")
                ScenarioRuntime.stop().await()
                val edit = preferences.edit()
                for (key in listOf("carrierIdentityEnabled", "carrierIdentityJson")) when (val value = saved[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                }
                check(edit.commit())
                MockServiceHelper.putConfig(location, context)
                ScenarioRuntime.selectBackend(previousBackend).await()
            } } finally {
                automation.serviceInfo = originalServiceInfo.apply { flags = originalFlags }
                automation.dropShellPermissionIdentity()
            }
        }
    }
}
