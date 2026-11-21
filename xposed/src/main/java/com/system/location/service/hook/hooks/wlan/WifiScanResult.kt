package com.system.location.service.hook.hooks.wlan

import android.net.wifi.ScanResult
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.hook.runtime.Reflection

internal fun syntheticWifiScanResult(ap: WifiAp, observedMicros: Long): ScanResult =
    ScanResult().apply {
        SSID = ap.ssid
        BSSID = ap.bssid.lowercase()
        capabilities = ap.capabilities
        frequency = ap.frequencyMhz
        level = ap.rssi
        timestamp = observedMicros
        channelWidth = if (ap.frequencyMhz >= 5_000) ScanResult.CHANNEL_WIDTH_80MHZ
            else ScanResult.CHANNEL_WIDTH_20MHZ
        centerFreq0 = if (ap.frequencyMhz >= 5_000) ap.frequencyMhz + 30 else ap.frequencyMhz
        centerFreq1 = 0
        // A default ScanResult has a null array. Android's getter calls Arrays.asList(array),
        // which crashes even when a caller only wants to iterate the information elements.
        Reflection.setObjectField(this, "informationElements", emptyArray<ScanResult.InformationElement>())
        runCatching { Reflection.setIntField(this, "wifiStandard", ap.standard) }
    }
