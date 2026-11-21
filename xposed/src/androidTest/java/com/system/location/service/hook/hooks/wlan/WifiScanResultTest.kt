package com.system.location.service.hook.hooks.wlan

import android.net.wifi.ScanResult
import android.os.Parcel
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.ProceduralWifiSource
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiScanResultTest {
    @Test fun informationElementsAreReadableBeforeAndAfterBinderParceling() {
        val profile = ProceduralWifiSource(EnvironmentConfig(apPerCell = 4))
            .profileAt(Wgs84(31.2, 121.5), 42L, 1_000L)
        assertTrue(profile.wifi.isNotEmpty())
        profile.wifi.forEach { ap ->
            val result = syntheticWifiScanResult(ap, 123_456L)
            assertTrue(result.informationElements.isEmpty())
            assertEquals(ap.standard, result.wifiStandard)
            if (Build.VERSION.SDK_INT >= 33) assertArrayEquals(ap.ssid.toByteArray(Charsets.UTF_8),
                requireNotNull(result.wifiSsid).bytes)
            val parcel = Parcel.obtain()
            try {
                result.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val restored = ScanResult.CREATOR.createFromParcel(parcel)
                assertTrue(restored.informationElements.isEmpty())
                assertEquals(ap.bssid.lowercase(), restored.BSSID)
                assertEquals(ap.ssid, restored.SSID)
                assertEquals(123_456L, restored.timestamp)
                assertEquals(ap.standard, restored.wifiStandard)
                if (Build.VERSION.SDK_INT >= 33) assertArrayEquals(ap.ssid.toByteArray(Charsets.UTF_8),
                    requireNotNull(restored.wifiSsid).bytes)
            } finally { parcel.recycle() }
        }
    }
}
