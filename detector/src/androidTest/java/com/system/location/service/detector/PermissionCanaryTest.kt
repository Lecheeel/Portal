package com.system.location.service.detector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Run this from a third-party UID after the Portal scenario is active.
 *
 * The three methods are intentionally separate: the host grants/revokes detector permissions
 * between runs, so the instrumentation process always starts with the intended permission state.
 */
@RunWith(AndroidJUnit4::class)
class PermissionCanaryTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun syntheticScenarioIsVisibleAndMockFlagIsHidden() {
        val manager = context.getSystemService(LocationManager::class.java)
        val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: awaitCurrent(manager)
        assertNotNull("scenario produced no location", location)
        assertFalse("Portal must hide the mock flag by default", location!!.isMock)
        assertTrue("unexpected latitude ${location.latitude}",
            kotlin.math.abs(location.latitude - 31.2304) < 0.05)
        assertTrue("unexpected longitude ${location.longitude}",
            kotlin.math.abs(location.longitude - 121.4737) < 0.05)

        val reply = Bundle()
        assertTrue("gps extra commands must pass through while simulating",
            manager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", reply))
        assertTrue("gps provider should be reported enabled",
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    }

    @Test
    fun noPermissionCanariesAreNotBypassed() {
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = NoopListener()
        assertThrows(SecurityException::class.java) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener)
        }
        assertThrows(SecurityException::class.java) {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }

        val wifi = context.getSystemService(WifiManager::class.java)
        val scans = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        assertTrue("no-permission scan results must stay empty, got ${scans.size}", scans.isEmpty())
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        if (info != null) {
            val redacted = info.ssid.contains("unknown", ignoreCase = true) ||
                info.bssid == "02:00:00:00:00:00" || info.bssid.isNullOrBlank()
            assertTrue("Wi-Fi identity was not redacted: ssid=${info.ssid} bssid=${info.bssid}", redacted)
        }

        val telephony = context.getSystemService(TelephonyManager::class.java)
        val cells = runCatching { telephony.allCellInfo }.getOrNull().orEmpty()
        assertTrue("no-permission cell info must stay empty, got ${cells.size}", cells.isEmpty())
        assertThrows(SecurityException::class.java) { telephony.dataNetworkType }
    }

    @Test
    fun coarseOnlyCallerGetsCoarseFix() {
        val manager = context.getSystemService(LocationManager::class.java)
        val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: awaitCurrent(manager)
        assertNotNull("coarse caller produced no location", location)
        assertTrue("coarse accuracy ${location!!.accuracy} must be >= 2000 m",
            location.accuracy >= 1_999f)
        assertEquals("network", location.provider)
    }

    private fun awaitCurrent(manager: LocationManager): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null
        val signal = android.os.CancellationSignal()
        manager.getCurrentLocation(
            LocationManager.GPS_PROVIDER,
            signal,
            context.mainExecutor,
        ) { location ->
            result = location
            latch.countDown()
        }
        latch.await(8, TimeUnit.SECONDS)
        signal.cancel()
        return result
    }

    private class NoopListener : LocationListener {
        override fun onLocationChanged(location: Location) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
}
