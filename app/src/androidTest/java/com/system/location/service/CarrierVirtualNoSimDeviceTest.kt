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
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE,
            "android.permission.READ_PRIVILEGED_PHONE_STATE")
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
            assertTrue(info.subscriptionId in 1..65535)
            assertEquals(com.system.location.service.core.carrier.CarrierIdentityCodec.decode(
                preferences.getString("carrierIdentityJson", "")!!).sims.single().virtualSubscriptionId, info.subscriptionId)
            assertEquals("T-Mobile", info.carrierName.toString())
            assertEquals("310", info.mccString)
            assertEquals("260", info.mncString)
            val persisted = com.system.location.service.core.carrier.CarrierIdentityCodec.decode(
                preferences.getString("carrierIdentityJson", "")!!).sims.single()
            assertEquals(CarrierVirtualIdentity.iccid(persisted), info.iccId)

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
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE,
            "android.permission.READ_PRIVILEGED_PHONE_STATE")
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
            assertEquals(2, subscriptions.activeSubscriptionInfoCount)
            for (item in active) {
                assertTrue("Virtual subscription is not active", subscriptions.isActiveSubscriptionId(item.subscriptionId))
                assertEquals(item.simSlotIndex, SubscriptionManager.getSlotIndex(item.subscriptionId))
                assertEquals(item.subscriptionId,
                    subscriptions.getActiveSubscriptionInfoForSimSlotIndex(item.simSlotIndex)?.subscriptionId)
                assertEquals(item.simSlotIndex,
                    subscriptions.getActiveSubscriptionInfo(item.subscriptionId)?.simSlotIndex)
            }
            val first = active.first { it.simSlotIndex == 0 }
            val second = active.first { it.simSlotIndex == 1 }
            assertTrue(first.subscriptionId in 1..65535 && second.subscriptionId in 1..65535)
            assertTrue(first.subscriptionId != second.subscriptionId)
            val assignedProfiles = com.system.location.service.core.carrier.CarrierIdentityCodec.decode(
                preferences.getString("carrierIdentityJson", "")!!).sims.associateBy { it.slot }
            assertEquals(assignedProfiles.getValue(1).virtualSubscriptionId, first.subscriptionId)
            assertEquals(assignedProfiles.getValue(2).virtualSubscriptionId, second.subscriptionId)
            assertEquals("T-Mobile", first.carrierName.toString())
            assertEquals("Telstra", second.carrierName.toString())
            assertEquals("310", first.mccString)
            assertEquals("260", first.mncString)
            assertEquals("505", second.mccString)
            assertEquals("01", second.mncString)
            val persisted = com.system.location.service.core.carrier.CarrierIdentityCodec.decode(
                preferences.getString("carrierIdentityJson", "")!!).sims.associateBy { it.slot }
            assertEquals(CarrierVirtualIdentity.iccid(persisted.getValue(1)), first.iccId)
            assertEquals(CarrierVirtualIdentity.iccid(persisted.getValue(2)), second.iccId)
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
            try {
                val redacted = subscriptions.activeSubscriptionInfoList.orEmpty()
                assertEquals(2, redacted.size)
                assertTrue("READ_PHONE_STATE must not reveal ICCID", redacted.all { it.iccId.isNullOrEmpty() })
                val reply = android.os.Bundle().apply {
                    putString("kind", "phone"); putInt("id", 0); putBoolean("identifiers", true)
                    putString("imsi", "must be removed"); putString("iccid", "must be removed")
                }
                location.sendExtraCommand("fused_ext",
                    com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.COMMAND, reply)
                val encoded = reply.getString("profile")!!
                val publicProfile = Json.decodeFromString(SimCarrierProfile.serializer(), encoded)
                assertEquals(0L, publicProfile.identitySeed)
                assertTrue(!encoded.contains("identitySeed"))
                assertTrue(!reply.containsKey("imsi"))
                assertTrue(!reply.containsKey("iccid"))
            } finally {
                automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE,
                    "android.permission.READ_PRIVILEGED_PHONE_STATE")
            }
            val privilegedReply = android.os.Bundle().apply {
                putString("kind", "phone"); putInt("id", 0); putBoolean("identifiers", true)
            }
            location.sendExtraCommand("fused_ext",
                com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.COMMAND, privilegedReply)
            assertEquals(CarrierVirtualIdentity.imsi(persisted.getValue(1)), privilegedReply.getString("imsi"))
            assertEquals(CarrierVirtualIdentity.iccid(persisted.getValue(1)), privilegedReply.getString("iccid"))
            assertTrue(!privilegedReply.getString("profile")!!.contains("identitySeed"))
            privilegedReply.putBoolean("identifiers", false)
            location.sendExtraCommand("fused_ext",
                com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.COMMAND, privilegedReply)
            assertTrue(!privilegedReply.containsKey("imsi") && !privilegedReply.containsKey("iccid"))
            if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("direct_subscriber") == "true") {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "iphonesubinfo")
                assertNotNull("IPhoneSubInfo binder must exist", binder)
                val stub = Class.forName("com.android.internal.telephony.IPhoneSubInfo\$Stub")
                val remote = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                fun readIdentifier(name: String, id: Int): String? {
                    val method = remote.javaClass.methods.single { it.name == name && it.parameterCount == 3 }
                    return try { method.invoke(remote, id, context.packageName, null) as? String }
                    catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                }
                for (item in active) {
                    val expected = persisted.getValue(item.simSlotIndex + 1)
                    assertEquals(CarrierVirtualIdentity.imsi(expected),
                        readIdentifier("getSubscriberIdForSubscriber", item.subscriptionId))
                    assertEquals(CarrierVirtualIdentity.iccid(expected),
                        readIdentifier("getIccSerialNumberForSubscriber", item.subscriptionId))
                }
                val phoneBinder = serviceManager.getMethod("getService", String::class.java).invoke(null, "phone")
                val phoneStub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                val phone = phoneStub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, phoneBinder)
                for (item in active) {
                    val expected = persisted.getValue(item.simSlotIndex + 1)
                    for (name in listOf("getSubscriptionCarrierId", "getSubscriptionSpecificCarrierId")) {
                        assertEquals(CarrierVirtualIdentity.carrierId(expected), phone.javaClass.getMethod(name,
                            Int::class.javaPrimitiveType).invoke(phone, item.subscriptionId))
                    }
                    for (name in listOf("getSubscriptionCarrierName", "getSubscriptionSpecificCarrierName")) {
                        assertEquals(expected.carrierName, phone.javaClass.getMethod(name,
                            Int::class.javaPrimitiveType).invoke(phone, item.subscriptionId)?.toString())
                    }
                }
                automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE)
                try {
                    for (name in listOf("getSubscriberIdForSubscriber", "getIccSerialNumberForSubscriber")) {
                        try {
                            assertTrue("Direct binder leaked $name without privileged permission",
                                readIdentifier(name, first.subscriptionId).isNullOrEmpty())
                        } catch (_: SecurityException) { /* Platform rejection remains intact. */ }
                    }
                } finally {
                    automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE,
                        "android.permission.READ_PRIVILEGED_PHONE_STATE")
                }
            }
            System.err.println("PORTAL_VIRTUAL_DUAL_SLOT_READY ${first.subscriptionId} ${second.subscriptionId}")
            val seeded = com.system.location.service.core.carrier.CarrierIdentityCodec.decode(
                preferences.getString("carrierIdentityJson", "")!!)
            fun applyIdentity(next: com.system.location.service.core.carrier.CarrierIdentityConfig) {
                check(preferences.edit().putString("carrierIdentityJson",
                    com.system.location.service.core.carrier.CarrierIdentityCodec.encode(next)).commit())
                assertTrue(MockServiceHelper.putConfig(location, context))
            }
            fun awaitIds(ids: Set<Int>) {
                val until = android.os.SystemClock.elapsedRealtime() + 5000
                while ((subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.toSet() != ids ||
                    subscriptions.activeSubscriptionInfoCount != ids.size) &&
                    android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100)
                assertEquals(ids, subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.toSet())
                assertEquals(ids.size, subscriptions.activeSubscriptionInfoCount)
            }
            val unlisted = seeded.copy(mode = com.system.location.service.core.carrier.CarrierIdentityMode.PER_APP,
                appRoutes = emptyList())
            applyIdentity(unlisted)
            awaitIds(emptySet())
            val hidden = android.os.Bundle().apply { putString("kind", "phone"); putInt("id", 1) }
            location.sendExtraCommand("fused_ext",
                com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.COMMAND, hidden)
            assertTrue("Unlisted caller received a virtual profile", !hidden.containsKey("profile"))
            val simTwoOnly = unlisted.copy(appRoutes = listOf(com.system.location.service.core.carrier.CarrierAppRoute(
                context.packageName, com.system.location.service.core.carrier.CarrierTarget.SIM2)))
            applyIdentity(simTwoOnly)
            awaitIds(setOf(second.subscriptionId))
            assertEquals(second.subscriptionId, subscriptions.getActiveSubscriptionInfoForSimSlotIndex(1)?.subscriptionId)
            val selected = android.os.Bundle().apply { putString("kind", "phone"); putInt("id", 0) }
            location.sendExtraCommand("fused_ext",
                com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.COMMAND, selected)
            val selectedProfile = Json.decodeFromString(SimCarrierProfile.serializer(), selected.getString("profile")!!)
            assertEquals(2, selectedProfile.slot)
            assertEquals("50501", selectedProfile.operatorNumeric)
            val real = unlisted.copy(appRoutes = listOf(com.system.location.service.core.carrier.CarrierAppRoute(
                context.packageName, com.system.location.service.core.carrier.CarrierTarget.REAL)))
            applyIdentity(real)
            awaitIds(emptySet())
            applyIdentity(seeded)
            awaitIds(setOf(first.subscriptionId, second.subscriptionId))
            val physicalOnlySecond = seeded.copy(sims = seeded.sims.map {
                if (it.slot == 2) it.copy(virtual = false) else it
            })
            check(preferences.edit().putString("carrierIdentityJson",
                com.system.location.service.core.carrier.CarrierIdentityCodec.encode(physicalOnlySecond)).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            val removalDeadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (subscriptions.activeSubscriptionInfoList.orEmpty().size != 1 &&
                android.os.SystemClock.elapsedRealtime() < removalDeadline) Thread.sleep(100)
            assertEquals(listOf(first.subscriptionId), subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId })
            assertEquals(1, subscriptions.activeSubscriptionInfoCount)
            assertTrue(!subscriptions.isActiveSubscriptionId(second.subscriptionId))
            assertEquals(SubscriptionManager.INVALID_SIM_SLOT_INDEX, SubscriptionManager.getSlotIndex(second.subscriptionId))
            val allPhysicalOnly = physicalOnlySecond.copy(sims = physicalOnlySecond.sims.map { it.copy(virtual = false) })
            check(preferences.edit().putString("carrierIdentityJson",
                com.system.location.service.core.carrier.CarrierIdentityCodec.encode(allPhysicalOnly)).commit())
            assertTrue(MockServiceHelper.putConfig(location, context))
            val emptyDeadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (subscriptions.activeSubscriptionInfoList.orEmpty().isNotEmpty() &&
                android.os.SystemClock.elapsedRealtime() < emptyDeadline) Thread.sleep(100)
            assertTrue(subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            assertEquals(0, subscriptions.activeSubscriptionInfoCount)
            assertEquals("no_active_sim", MockServiceHelper.carrierIdentityStatus)
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
