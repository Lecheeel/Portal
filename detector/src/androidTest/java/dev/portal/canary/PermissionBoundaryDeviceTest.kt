package dev.portal.canary

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Host sets permissions and location mode before each fresh instrumentation process. */
class PermissionBoundaryDeviceTest : CanaryDeviceFixture() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(LocationManager::class.java)

    private fun report(name: String, vararg values: Pair<String, Any?>) {
        val json = JSONObject().put("check", name).put("uid", android.os.Process.myUid())
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("canary", json.toString())
        })
    }

    private fun noLocationPermission() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test fun updatesWithoutPermission() {
        noLocationPermission()
        val listener = LocationListener { }
        val failure = runCatching {
            manager.requestLocationUpdates("gps", 0L, 0f, listener, Looper.getMainLooper())
        }.exceptionOrNull()
        runCatching { manager.removeUpdates(listener) }
        report("updatesWithoutPermission", "exception" to failure?.javaClass?.name)
        assertTrue("Expected SecurityException, got $failure", failure is SecurityException)
    }

    @Test fun wifiScansWithoutPermission() {
        noLocationPermission()
        val result = runCatching { context.getSystemService(WifiManager::class.java).scanResults }
        report("wifiScansWithoutPermission", "count" to result.getOrNull()?.size,
            "exception" to result.exceptionOrNull()?.javaClass?.name)
        assertTrue(result.exceptionOrNull() is SecurityException || result.getOrNull()?.isEmpty() == true)
    }

    @Suppress("DEPRECATION")
    @Test fun wifiConnectionWithoutPermission() {
        noLocationPermission()
        val result = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo }
        val info = result.getOrNull()
        report("wifiConnectionWithoutPermission", "ssid" to info?.ssid, "bssid" to info?.bssid,
            "mac" to info?.macAddress, "exception" to result.exceptionOrNull()?.javaClass?.name)
        if (result.exceptionOrNull() is SecurityException) return
        assertNotNull(info)
        assertTrue("SSID exposed", info!!.ssid == "<unknown ssid>" || info.ssid.isNullOrBlank())
        assertTrue("BSSID exposed", info.bssid == "02:00:00:00:00:00" || info.bssid.isNullOrBlank())
        assertTrue("MAC exposed", info.macAddress == "02:00:00:00:00:00" || info.macAddress.isNullOrBlank())
    }

    @Test fun cellsWithoutPermission() {
        noLocationPermission()
        val result = runCatching { context.getSystemService(TelephonyManager::class.java).allCellInfo }
        report("cellsWithoutPermission", "count" to result.getOrNull()?.size,
            "exception" to result.exceptionOrNull()?.javaClass?.name)
        assertTrue(result.exceptionOrNull() is SecurityException || result.getOrNull()?.isEmpty() == true)
    }

    @Test fun networkTypeWithoutPhonePermission() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE))
        val result = runCatching { context.getSystemService(TelephonyManager::class.java).dataNetworkType }
        report("networkTypeWithoutPhonePermission", "value" to result.getOrNull(),
            "exception" to result.exceptionOrNull()?.javaClass?.name)
        assertTrue("Expected SecurityException", result.exceptionOrNull() is SecurityException)
    }

    @Test fun extraCommandReturnsNormally() {
        val result = runCatching { manager.sendExtraCommand("gps", "force_xtra_injection", Bundle()) }
        report("extraCommand", "return" to result.getOrNull(), "exception" to result.exceptionOrNull()?.toString())
        assertNull(result.exceptionOrNull())
    }

    @Test fun fineScenarioFix() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        val fix = manager.getLastKnownLocation("gps")
        report("fineFix", "provider" to fix?.provider, "accuracy" to fix?.accuracy,
            "latitude" to fix?.latitude, "longitude" to fix?.longitude, "mock" to fix?.isMock)
        assertNotNull(fix)
        assertTrue(kotlin.math.abs(fix!!.latitude - 31.2304) < 0.01)
        assertTrue(kotlin.math.abs(fix.longitude - 121.4737) < 0.01)
    }

}
