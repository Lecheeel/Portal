@file:Suppress("UNCHECKED_CAST", "PrivateApi")
package com.system.location.service.hook.hooks.wlan

import android.net.DhcpInfo
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.os.Binder
import android.os.Build
import android.os.SystemClock
import android.util.ArrayMap
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.environment.FingerprintWifiSource
import com.system.location.service.core.environment.ProceduralWifiSource
import com.system.location.service.core.environment.ReplayEnvironmentSource
import com.system.location.service.core.environment.WifiFingerprintImporter
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.hook.hooks.ListenerRouting
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.toClass

/**
 * P2-04: replace the old empty-list/sentinel-BSSID WiFi answers with the deterministic procedural
 * environment (P2-03). Only third-party callers are rewritten; system callers keep the real state so
 * framework services and Settings behave normally.
 */
object WlanHook {
    private var source = ProceduralWifiSource(EnvironmentConfig(apPerCell = 4))
    private var sourceApCount = 4
    private val profileLock = Any()
    private var cachedKey: String? = null
    private var cachedProfile: EnvironmentProfile? = null
    private var cachedFingerprintJson: String? = null
    private var cachedFingerprintSource: FingerprintWifiSource? = null

    operator fun invoke(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cSystemServerClassLoaderFactory = Reflection.findClassIfExists(
                "com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                Logger.warn("Failed to find SystemServerClassLoaderFactory")
                return
            }
            val sLoadedPaths = Reflection.getStaticObjectField(
                cSystemServerClassLoaderFactory, "sLoadedPaths") as ArrayMap<String, PathClassLoader>
            val wifiClassLoader = sLoadedPaths.firstNotNullOfOrNull {
                if (it.key.contains("service-wifi.jar")) it.value else null
            }
            if (wifiClassLoader == null) {
                Logger.warn("Failed to find wifiClassLoader")
                return
            }
            val wifiClazz = "com.android.server.wifi.WifiServiceImpl".toClass(wifiClassLoader)
            if (wifiClazz == null) {
                Logger.warn("Failed to find WifiServiceImpl class")
                return
            }
            hookWifiServiceImpl(wifiClazz)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cSystemServiceManager = Reflection.findClassIfExists(
                "com.android.server.SystemServiceManager", classLoader)
            if (cSystemServiceManager == null) {
                Logger.warn("Failed to find SystemServiceManager")
                return
            }
            cSystemServiceManager.hookAllMethods("loadClassFromLoader", Hooker { chain ->
                val original = chain.proceed()
                if (chain.args[0] == "com.android.server.wifi.WifiService") {
                    kotlin.runCatching {
                        val classloader = chain.args[1] as PathClassLoader
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        hookWifiServiceImpl(wifiClazz)
                    }.onFailure {
                        Logger.error("Failed to hook WifiService", it)
                    }
                }
                original
            })
        }
    }

    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
        if (!FakeLoc.hookWifi) return

        wifiClazz.hookAllMethods("getConnectionInfo", Hooker { chain ->
            // Run the framework path first. It enforces the caller's location permission and
            // returns a redacted/disconnected WifiInfo when the caller may not see SSID/BSSID.
            val original = chain.proceed() as? WifiInfo ?: return@Hooker null
            val packageName = chain.args.firstOrNull() as? String
            if (packageName.isNullOrEmpty() || !shouldRewrite(packageName)) return@Hooker original
            if (!isRealWifiConnected(original)) return@Hooker original
            val info = buildWifiInfo(currentProfile(packageName), original) ?: return@Hooker original
            if (FakeLoc.enableDebugLog) Logger.debug("getConnectionInfo: procedural $packageName")
            info
        })

        wifiClazz.hookAllMethods("getScanResults", Hooker { chain ->
            // Preserve an empty result: it carries the real permission/Wi-Fi-off state.
            val original = chain.proceed()
            val packageName = chain.args.firstOrNull() as? String
            if (packageName.isNullOrEmpty() || !shouldRewrite(packageName)) return@Hooker original
            if (scanResultCount(original) == 0) return@Hooker original
            val generated = buildScanResults(currentProfile(packageName))
            if (generated.isEmpty()) return@Hooker original
            val result = when (val existing = original) {
                null -> {
                    val returnType = (chain.executable as? java.lang.reflect.Method)?.returnType
                    if (returnType != null && returnType.name.contains("ParceledListSlice")) {
                        sliceOrNull(returnType, generated) ?: return@Hooker original
                    } else {
                        generated
                    }
                }
                is List<*> -> generated
                is Array<*> -> generated.toTypedArray()
                else -> sliceOrNull(existing.javaClass, generated) ?: return@Hooker original
            }
            if (FakeLoc.enableDebugLog) Logger.debug("getScanResults: ${generated.size} procedural APs for $packageName")
            result
        })
    }

    private fun isRealWifiConnected(info: WifiInfo): Boolean =
        info.supplicantState == SupplicantState.COMPLETED &&
            !info.ssid.isNullOrBlank() &&
            !info.ssid.contains("unknown ssid", ignoreCase = true) &&
            !info.bssid.isNullOrBlank() &&
            info.bssid != REDACTED_BSSID

    private fun scanResultCount(value: Any?): Int = when (value) {
        null -> 0
        is List<*> -> value.size
        is Array<*> -> value.size
        else -> runCatching {
            (Reflection.callMethod(value, "getList") as? List<*>)?.size ?: 1
        }.getOrDefault(1)
    }

    private fun shouldRewrite(packageName: String): Boolean =
        FakeLoc.enable && FakeLoc.enableMockWifi && !BinderUtils.isSystemPackages(packageName)

    private fun currentProfile(packageName: String? = null): EnvironmentProfile {
        val point = ListenerRouting.fixedPointFor(packageName)
            ?: Wgs84(FakeLoc.latitude, FakeLoc.longitude)
        val fingerprintJson = FakeLoc.wifiFingerprintCatalogJson
        val fingerprintVersion = if (FakeLoc.wifiFingerprintEnabled) fingerprintJson.hashCode() else 0
        val key = "${point.latitude}:${point.longitude}:${FakeLoc.environmentSeed}:${FakeLoc.environmentApCount}:$fingerprintVersion"
        synchronized(profileLock) {
            cachedProfile?.let { if (cachedKey == key) return it }
            val now = System.currentTimeMillis()
            val fingerprint = fingerprintProfile(point, now)
            val replay = if (FakeLoc.environmentSourceMode == EnvironmentSourceMode.PROCEDURAL) null else FakeLoc.replayEnvironment
            val result = fingerprint
                ?: replay?.let { profile -> runCatching { ReplayEnvironmentSource(profile).profileAt(point, FakeLoc.environmentSeed, now) }.getOrNull() }
                ?: source().profileAt(point, FakeLoc.environmentSeed, now)
            return result.also {
                cachedKey = key
                cachedProfile = it
            }
        }
    }

    private fun fingerprintProfile(point: Wgs84, nowMillis: Long): EnvironmentProfile? {
        if (!FakeLoc.wifiFingerprintEnabled) return null
        val json = FakeLoc.wifiFingerprintCatalogJson?.takeIf { it.isNotBlank() } ?: return null
        if (cachedFingerprintJson != json) {
            cachedFingerprintJson = json
            cachedFingerprintSource = runCatching { FingerprintWifiSource(WifiFingerprintImporter.importJson(json).catalog.database()) }
                .onFailure { if (FakeLoc.enableDebugLog) Logger.debug("wifi fingerprint parse failed: ${it.message}") }
                .getOrNull()
        }
        return cachedFingerprintSource?.profileAt(point, FakeLoc.environmentSeed, nowMillis)
    }

    private fun source(): ProceduralWifiSource {
        if (sourceApCount != FakeLoc.environmentApCount) {
            source = ProceduralWifiSource(EnvironmentConfig(apPerCell = FakeLoc.environmentApCount))
            sourceApCount = FakeLoc.environmentApCount
            cachedKey = null
            cachedProfile = null
        }
        return source
    }

    private fun buildScanResults(profile: EnvironmentProfile): List<ScanResult> {
        val baseTimestamp = SystemClock.elapsedRealtimeNanos() / 1_000L
        return profile.wifi.mapIndexed { index, ap ->
            ScanResult().apply {
                SSID = ap.ssid
                // Android exposes BSSIDs in lower-case; the fake environment must not stand out.
                BSSID = ap.bssid.lowercase()
                capabilities = ap.capabilities
                frequency = ap.frequencyMhz
                level = ap.rssi
                // Real scan results are observed at slightly different instants.
                timestamp = baseTimestamp + index * 137L
                channelWidth = if (ap.frequencyMhz >= 5_000) ScanResult.CHANNEL_WIDTH_80MHZ
                    else ScanResult.CHANNEL_WIDTH_20MHZ
                // A 5 GHz 80 MHz segment is reported around its centre, not the primary channel.
                centerFreq0 = if (ap.frequencyMhz >= 5_000) ap.frequencyMhz + 30 else ap.frequencyMhz
                centerFreq1 = 0
                runCatching { Reflection.setIntField(this, "wifiStandard", standardFor(ap.frequencyMhz)) }
            }
        }
    }

    private fun buildWifiInfo(profile: EnvironmentProfile, original: WifiInfo): WifiInfo? {
        val ap = profile.connectedWifi() ?: return null
        val info = runCatching { Reflection.newInstance(WifiInfo::class.java) as WifiInfo }.getOrNull()
            ?: return null
        val linkSpeed = original.linkSpeed.takeIf { it > 0 }
            ?: if (ap.frequencyMhz >= 5_000) 433 else 144
        // Device-level facts belong to the real device. Only the environment (SSID/BSSID/RSSI/
        // channel) is synthetic, so a detector cannot cluster every Portal user by one MAC/IP.
        val mac = original.macAddress?.takeIf { it.isNotBlank() && it != REDACTED_BSSID }
            ?: fallbackMac()
        val values: List<Pair<String, Any>> = listOf<Pair<String, Any>>(
            "setBSSID" to ap.bssid.lowercase(),
            "setSSID" to "\"${ap.ssid}\"",
            "setMacAddress" to mac,
            "setNetworkId" to (original.networkId.takeIf { it >= 0 } ?: 0),
            "setRssi" to ap.rssi,
            "setFrequency" to ap.frequencyMhz,
            "setLinkSpeed" to linkSpeed,
            "setRxLinkSpeedMbps" to (original.rxLinkSpeedMbps.takeIf { it > 0 } ?: linkSpeed),
            "setTxLinkSpeedMbps" to (original.txLinkSpeedMbps.takeIf { it > 0 } ?: linkSpeed),
            "setMaxSupportedRxLinkSpeedMbps" to
                (original.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 } ?: linkSpeed),
            "setMaxSupportedTxLinkSpeedMbps" to
                (original.maxSupportedTxLinkSpeedMbps.takeIf { it > 0 } ?: linkSpeed),
            "setWifiStandard" to standardFor(ap.frequencyMhz),
            "setIpAddress" to original.ipAddress,
            "setSupplicantState" to SupplicantState.COMPLETED,
            "setHiddenSSID" to false,
        )
        values.forEach { (method, value) ->
            runCatching { Reflection.callMethod(info, method, value) }
                .onFailure { if (FakeLoc.enableDebugLog) Logger.debug("WifiInfo.$method unavailable: ${it.javaClass.simpleName}") }
        }
        return info
    }

    /** Stable, locally-administered fallback MAC when the real value is redacted. */
    private fun fallbackMac(): String {
        val random = kotlin.random.Random(
            FakeLoc.environmentSeed xor Build.FINGERPRINT.hashCode().toLong(),
        )
        return buildString {
            append("02")
            repeat(5) { append(':').append("%02x".format(random.nextInt(256))) }
        }
    }

    private fun standardFor(frequencyMhz: Int): Int = when {
        frequencyMhz >= 5_925 -> ScanResult.WIFI_STANDARD_11AX
        frequencyMhz >= 5_000 -> ScanResult.WIFI_STANDARD_11AC
        else -> ScanResult.WIFI_STANDARD_11N
    }

    private fun sliceOrNull(type: Class<*>, values: List<ScanResult>): Any? =
        if (type.name.contains("ParceledListSlice")) {
            runCatching {
                val constructor = type.getConstructor(List::class.java)
                if (!constructor.isAccessible) constructor.isAccessible = true
                constructor.newInstance(values)
            }.onFailure { Logger.error("getScanResults: ParceledListSlice failed", it) }.getOrNull()
        } else {
            Logger.error("getScanResults: unknown return type ${type.name}")
            null
        }

    private const val REDACTED_BSSID = "02:00:00:00:00:00"
}
