package com.system.location.service

import android.Manifest
import android.app.AppOpsManager
import android.location.LocationManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.telephony.SubscriptionManager
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

class CarrierPhoneNumberDeviceTest {
    @Test fun savedDualNumbersRespectPermissionAndAllPublicNumberInterfaces() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.sharedPrefs
        val saved = prefs.all
        val backend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE, "android.permission.READ_PRIVILEGED_PHONE_STATE")
        var started = false
        var opChanged = false
        var smsOpChanged = false
        val ops = context.getSystemService(AppOpsManager::class.java)
        val oldSmsOp = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_READ_SMS, android.os.Process.myUid(), context.packageName)
        val oldOp = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_READ_PHONE_NUMBERS, android.os.Process.myUid(), context.packageName)
        fun setOp(mode: String, operation: String = "READ_PHONE_NUMBERS") = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "cmd appops set ${context.packageName} $operation $mode")).use { it.readBytes() }
        fun modeName(mode: Int): String = when (mode) { AppOpsManager.MODE_ALLOWED -> "allow";
            AppOpsManager.MODE_IGNORED -> "ignore"; AppOpsManager.MODE_ERRORED -> "deny";
            AppOpsManager.MODE_FOREGROUND -> "foreground"; else -> "default" }
        try {
            assumeTrue("Requires no active SIM fixture", subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            started = true
            val cn = CarrierPhoneNumbers.generate("CN", "北京市", "CMCC")
            val au = CarrierPhoneNumbers.generate("AU")
            val config = CarrierIdentityConfig(sims = listOf(
                SimCarrierProfile(1, virtual = true, countryIso = "CN", mcc = 460, mnc = 0, mncDigits = 2,
                    carrierName = "CMCC", phoneNumber = cn, phoneNumberCity = "北京市"),
                SimCarrierProfile(2, virtual = true, countryIso = "AU", mcc = 505, mnc = 1, mncDigits = 2,
                    carrierName = "Telstra", phoneNumber = au)))
            check(prefs.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            assertTrue(ScenarioRuntime.start(Scenario("carrier-numbers", "Carrier numbers", point = Wgs84(39.9, 116.4))).await())
            assertTrue(MockServiceHelper.putConfig(location, context))
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            var active = subscriptions.activeSubscriptionInfoList.orEmpty()
            while (active.size != 2 && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(150); active = subscriptions.activeSubscriptionInfoList.orEmpty()
            }
            assertEquals(2, active.size)
            fun verifyNumbers() {
                for (info in subscriptions.activeSubscriptionInfoList.orEmpty()) {
                    val expected = if (info.simSlotIndex == 0) cn else au
                    val manager = telephony.createForSubscriptionId(info.subscriptionId)
                    repeat(2) {
                        @Suppress("DEPRECATION")
                        assertEquals("line1 SIM${info.simSlotIndex}", expected, manager.line1Number)
                        assertEquals("first available", expected, subscriptions.getPhoneNumber(info.subscriptionId))
                        assertEquals("UICC source", expected, subscriptions.getPhoneNumber(info.subscriptionId, 1))
                        @Suppress("DEPRECATION")
                        assertEquals("SubscriptionInfo.number", expected, info.number)
                    }
                    val reply = Bundle().apply { putString("kind", "phone"); putInt("id", info.simSlotIndex) }
                    assertTrue(location.sendExtraCommand("fused_ext", "portal_carrier_identity_snapshot", reply))
                    val exposed = kotlinx.serialization.json.Json.decodeFromString(SimCarrierProfile.serializer(), reply.getString("profile")!!)
                    assertEquals("Snapshot leaked phone number", "", exposed.phoneNumber)
                    assertEquals("Snapshot leaked city", "", exposed.phoneNumberCity)
                }
            }
            verifyNumbers()
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
            for (info in subscriptions.activeSubscriptionInfoList.orEmpty()) {
                @Suppress("DEPRECATION")
                assertTrue("Subscription list leaked number", info.number.isNullOrEmpty())
                try {
                    assertTrue(subscriptions.getPhoneNumber(info.subscriptionId).isEmpty())
                } catch (_: SecurityException) { }
                try {
                    @Suppress("DEPRECATION")
                    assertTrue(telephony.createForSubscriptionId(info.subscriptionId).line1Number.isNullOrEmpty())
                } catch (_: SecurityException) { }
            }
            setOp("allow", "READ_SMS"); smsOpChanged = true
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS)
            for (info in active) {
                @Suppress("DEPRECATION")
                assertEquals("READ_SMS legacy line1", if (info.simSlotIndex == 0) cn else au,
                    telephony.createForSubscriptionId(info.subscriptionId).line1Number)
                try {
                    assertTrue("Modern number API accepted legacy SMS permission", subscriptions.getPhoneNumber(info.subscriptionId).isEmpty())
                } catch (_: SecurityException) { }
            }
            setOp("allow"); opChanged = true
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
            verifyNumbers()
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE, "android.permission.READ_PRIVILEGED_PHONE_STATE")
            val off = CarrierIdentityCodec.decode(prefs.getString("carrierIdentityJson", "")!!)
                .copy(mode = CarrierIdentityMode.PER_APP)
            check(prefs.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(off)).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            val removedBy = android.os.SystemClock.elapsedRealtime() + 5000
            while (subscriptions.activeSubscriptionInfoList.orEmpty().isNotEmpty() &&
                android.os.SystemClock.elapsedRealtime() < removedBy) Thread.sleep(150)
            assertTrue("Unlisted caller retained virtual numbers", subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            for (info in active) {
                assertNotEquals(if (info.simSlotIndex == 0) cn else au, subscriptions.getPhoneNumber(info.subscriptionId))
            }
        } finally {
            try {
                if (opChanged) setOp(modeName(oldOp))
                if (smsOpChanged) setOp(modeName(oldSmsOp), "READ_SMS")
                if (started) {
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
                }
            } finally { automation.dropShellPermissionIdentity() }
        }
    }
}
