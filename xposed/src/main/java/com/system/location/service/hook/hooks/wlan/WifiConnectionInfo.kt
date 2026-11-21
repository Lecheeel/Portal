package com.system.location.service.hook.hooks.wlan

import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import java.net.InetAddress

internal fun syntheticWifiInfo(ap: WifiAp, seed: Long): WifiInfo {
    // WifiInfo.setSSID takes WifiSsid, not String. The public builder handles its platform type
    // and UTF-8 encoding on every supported Android version instead of silently leaving it null.
    val info = WifiInfo.Builder().setSsid(ap.ssid.toByteArray(Charsets.UTF_8))
        .setBssid(ap.bssid.lowercase()).setRssi(ap.rssi)
        .setNetworkId(ap.bssid.lowercase().hashCode() and Int.MAX_VALUE).build()
    val linkSpeed = if (ap.frequencyMhz >= 5_000) 433 else 144
    val ip = syntheticWifiIpv4(ap.bssid)
    val address = InetAddress.getByAddress(ByteArray(4) { (ip ushr (it * 8)).toByte() })
    listOf<Pair<String, Any>>(
        "setMacAddress" to syntheticWifiMac(seed, ap.bssid),
        "setFrequency" to ap.frequencyMhz,
        "setLinkSpeed" to linkSpeed,
        "setRxLinkSpeedMbps" to linkSpeed,
        "setTxLinkSpeedMbps" to linkSpeed,
        "setMaxSupportedRxLinkSpeedMbps" to linkSpeed,
        "setMaxSupportedTxLinkSpeedMbps" to linkSpeed,
        "setWifiStandard" to ap.standard,
        "setInetAddress" to address,
        "setSupplicantState" to SupplicantState.COMPLETED,
        "setHiddenSSID" to false,
    ).forEach { (method, value) ->
        runCatching { Reflection.callMethod(info, method, value) }.onFailure {
            if (FakeLoc.enableDebugLog) Logger.debug("WifiInfo.$method unavailable: ${it.javaClass.simpleName}")
        }
    }
    return info
}
