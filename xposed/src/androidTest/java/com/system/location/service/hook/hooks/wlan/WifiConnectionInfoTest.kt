package com.system.location.service.hook.hooks.wlan

import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.system.location.service.core.environment.WifiAp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiConnectionInfoTest {
    @Test fun connectedWifiHasAnEncodedSsidAndAnAddressAfterParceling() {
        for (ssid in listOf("Portal AP", "测试网络")) {
            val ap = WifiAp(ssid, "a4:50:46:12:34:56", -55, 2412, 1)
            val info = syntheticWifiInfo(ap, 42L)
            assertConnection(ap, info)
            val parcel = Parcel.obtain()
            try {
                parcel.writeParcelable(info, 0)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION")
                val restored = parcel.readParcelable<WifiInfo>(WifiInfo::class.java.classLoader)
                assertConnection(ap, requireNotNull(restored))
            } finally { parcel.recycle() }
        }
    }

    private fun assertConnection(ap: WifiAp, info: WifiInfo) {
        assertEquals("\"${ap.ssid}\"", info.ssid)
        assertEquals(ap.bssid, info.bssid)
        assertEquals(ap.rssi, info.rssi)
        assertEquals(ap.frequencyMhz, info.frequency)
        assertEquals(ap.standard, info.wifiStandard)
        assertEquals(syntheticWifiIpv4(ap.bssid), info.ipAddress)
        assertEquals(SupplicantState.COMPLETED, info.supplicantState)
    }
}
