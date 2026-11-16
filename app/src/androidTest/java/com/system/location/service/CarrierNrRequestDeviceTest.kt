package com.system.location.service

import android.Manifest
import android.location.LocationManager
import android.telephony.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CarrierNrRequestDeviceTest {
    @Test fun perSubscriptionRequestsReturnConfiguredNrBandChannelAndPlmn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val preferences = context.sharedPrefs
        val saved = preferences.all
        val backend = ScenarioRuntime.state.value.backend
        val location = context.getSystemService(LocationManager::class.java)
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        try {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            assumeTrue(subscriptions.activeSubscriptionInfoList.orEmpty().isEmpty())
            assertTrue(ScenarioRuntime.selectBackend(BackendType.XPOSED).await())
            val config = CarrierIdentityConfig(sims = listOf(
                SimCarrierProfile(1, virtual = true, countryIso = "US", mcc = 310, mnc = 260,
                    carrierName = "NR US", nrBand = 71, nrArfcn = 127000),
                SimCarrierProfile(2, virtual = true, countryIso = "AU", mcc = 505, mnc = 1,
                    mncDigits = 2, carrierName = "NR AU", nrBand = 78, nrArfcn = 640000)))
            check(preferences.edit().putBoolean("carrierIdentityEnabled", true)
                .putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            assertTrue(ScenarioRuntime.start(Scenario(id = "nr-request", name = "NR request",
                point = Wgs84(-33.8688, 151.2093))).await())
            assertTrue(MockServiceHelper.putConfig(location, context))
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (subscriptions.activeSubscriptionInfoList.orEmpty().size != 2 &&
                android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            val active = subscriptions.activeSubscriptionInfoList.orEmpty()
            assertEquals(2, active.size)
            val overview = context.getSystemService(TelephonyManager::class.java).allCellInfo.orEmpty()
            assertTrue("All-cell overview must include both virtual radios", overview.isNotEmpty())
            assertTrue(overview.all { it is CellInfoNr })
            val grouped = overview.filterIsInstance<CellInfoNr>().groupBy {
                val id = it.cellIdentity as CellIdentityNr
                id.mccString + id.mncString
            }
            assertEquals(config.sims.map { it.operatorNumeric }.toSet(), grouped.keys)
            config.sims.forEach { profile ->
                val cells = grouped.getValue(profile.operatorNumeric)
                assertEquals(1, cells.count { it.isRegistered })
                cells.forEach {
                    val id = it.cellIdentity as CellIdentityNr
                    assertArrayEquals(intArrayOf(profile.nrBand), id.bands)
                    assertEquals(profile.nrArfcn, id.nrarfcn)
                }
            }
            active.forEach { info ->
                val manager = context.getSystemService(TelephonyManager::class.java)
                    .createForSubscriptionId(info.subscriptionId)
                val profileForListener = config.sims.first { it.slot == info.simSlotIndex + 1 }
                val notifications = java.util.concurrent.LinkedBlockingQueue<List<CellInfo>>()
                val listener = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) { notifications.offer(cellInfo.toList()) }
                }
                manager.registerTelephonyCallback({ it.run() }, listener)
                try {
                    val until = android.os.SystemClock.elapsedRealtime() + 5000
                    var initial: List<CellInfo>? = null
                    do {
                        val value = notifications.poll(200, TimeUnit.MILLISECONDS)
                        if (!value.isNullOrEmpty()) { initial = value; break }
                    } while (android.os.SystemClock.elapsedRealtime() < until)
                    assertNotNull("Continuous listener did not receive virtual cells", initial)
                    assertEquals(1, initial!!.count { it.isRegistered })
                    initial!!.forEach { cell ->
                        assertTrue(cell is CellInfoNr)
                        val id = (cell as CellInfoNr).cellIdentity as CellIdentityNr
                        assertEquals(profileForListener.operatorNumeric, id.mccString + id.mncString)
                        assertArrayEquals(intArrayOf(profileForListener.nrBand), id.bands)
                        assertEquals(profileForListener.nrArfcn, id.nrarfcn)
                    }
                    fun awaitCells(label: String, matches: (List<CellInfo>) -> Boolean) {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                        do {
                            val value = notifications.poll(200, TimeUnit.MILLISECONDS)
                            if (value != null && matches(value)) return
                        } while (android.os.SystemClock.elapsedRealtime() < deadline)
                        fail(label)
                    }
                    val persisted = CarrierIdentityCodec.decode(preferences.getString("carrierIdentityJson", "")!!)
                    val changed = persisted.copy(sims = persisted.sims.map {
                        if (it.slot == info.simSlotIndex + 1) it.copy(nrBand = 77, nrArfcn = 650000) else it
                    })
                    notifications.clear()
                    check(preferences.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(changed)).commit())
                    assertTrue(MockServiceHelper.putConfig(location, context))
                    awaitCells("Active cell listener did not receive new NR channel") { cells ->
                        cells.isNotEmpty() && cells.all {
                            it is CellInfoNr && (it.cellIdentity as CellIdentityNr).let { id ->
                                id.nrarfcn == 650000 && id.bands.contentEquals(intArrayOf(77)) &&
                                    id.mccString + id.mncString == profileForListener.operatorNumeric
                            }
                        }
                    }
                    notifications.clear()
                    check(preferences.edit().putBoolean("carrierIdentityEnabled", false).commit())
                    assertTrue(MockServiceHelper.putConfig(location, context))
                    awaitCells("Disabled cell listener retained virtual cells") { it.isEmpty() }
                    notifications.clear()
                    check(preferences.edit().putBoolean("carrierIdentityEnabled", true)
                        .putString("carrierIdentityJson", CarrierIdentityCodec.encode(persisted)).commit())
                    assertTrue(MockServiceHelper.putConfig(location, context))
                    awaitCells("Re-enabled cell listener did not receive cells") { cells ->
                        cells.isNotEmpty() && cells.all { it is CellInfoNr &&
                            (it.cellIdentity as CellIdentityNr).nrarfcn == profileForListener.nrArfcn }
                    }
                } finally { manager.unregisterTelephonyCallback(listener) }
                notifications.clear()
                val afterUnregister = CarrierIdentityCodec.decode(preferences.getString("carrierIdentityJson", "")!!)
                check(preferences.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(
                    afterUnregister.copy(sims = afterUnregister.sims.map { it.copy(operatorName = "NR updated") }))).commit())
                assertTrue(MockServiceHelper.putConfig(location, context))
                assertNull("Unregistered cell listener received an update", notifications.poll(600, TimeUnit.MILLISECONDS))
                val latch = CountDownLatch(1)
                val result = AtomicReference<List<CellInfo>>()
                val error = AtomicReference<String>()
                manager.requestCellInfoUpdate({ it.run() }, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cells: MutableList<CellInfo>) { result.set(cells); latch.countDown() }
                    override fun onError(code: Int, detail: Throwable?) { error.set("$code: $detail"); latch.countDown() }
                })
                assertTrue("No callback for slot ${info.simSlotIndex}", latch.await(10, TimeUnit.SECONDS))
                assertNull("Virtual NR request failed: ${error.get()}", error.get())
                val cells = result.get().orEmpty()
                assertTrue("Virtual NR list must be populated", cells.isNotEmpty())
                assertEquals(1, cells.count { it.isRegistered })
                val profile = config.sims.first { it.slot == info.simSlotIndex + 1 }
                cells.forEach { cell ->
                    assertTrue(cell is CellInfoNr)
                    val identity = (cell as CellInfoNr).cellIdentity as CellIdentityNr
                    assertEquals(profile.operatorNumeric, identity.mccString + identity.mncString)
                    assertEquals(profile.nrArfcn, identity.nrarfcn)
                    assertArrayEquals(intArrayOf(profile.nrBand), identity.bands)
                    assertTrue(identity.nci in 0 until (1L shl 36))
                    assertTrue(identity.pci in 0..1007)
                }
            }
        } finally {
            try {
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
