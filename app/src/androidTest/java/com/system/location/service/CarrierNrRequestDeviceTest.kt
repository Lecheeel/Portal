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
