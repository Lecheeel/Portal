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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
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
import com.system.location.service.hook.utils.afterHook
import com.system.location.service.hook.utils.beforeHook
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
            val cSystemServerClassLoaderFactory = XposedHelpers.findClassIfExists(
                "com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                Logger.warn("Failed to find SystemServerClassLoaderFactory")
                return
            }
            val sLoadedPaths = XposedHelpers.getStaticObjectField(
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
            val cSystemServiceManager = XposedHelpers.findClassIfExists(
                "com.android.server.SystemServiceManager", classLoader)
            if (cSystemServiceManager == null) {
                Logger.warn("Failed to find SystemServiceManager")
                return
            }
            cSystemServiceManager.hookAllMethods("loadClassFromLoader", afterHook {
                if (args[0] == "com.android.server.wifi.WifiService") {
                    kotlin.runCatching {
                        val classloader = args[1] as PathClassLoader
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        hookWifiServiceImpl(wifiClazz)
                    }.onFailure {
                        Logger.error("Failed to hook WifiService", it)
                    }
                }
            })
        }
    }

    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
        if (!FakeLoc.hookWifi) return

        wifiClazz.hookAllMethods("getConnectionInfo", beforeHook {
            val packageName = args.firstOrNull() as? String
            if (packageName.isNullOrEmpty() || !shouldRewrite(packageName)) return@beforeHook
            val info = buildWifiInfo(currentProfile(packageName)) ?: return@beforeHook
            result = info
            if (FakeLoc.enableDebugLog) Logger.debug("getConnectionInfo: procedural $packageName")
        })

        wifiClazz.hookAllMethods("getScanResults", afterHook {
            val packageName = args.firstOrNull() as? String
            if (packageName.isNullOrEmpty() || !shouldRewrite(packageName)) return@afterHook
            val generated = buildScanResults(currentProfile(packageName))
            if (generated.isEmpty()) return@afterHook
            result = when (val existing = result) {
                null -> {
                    val returnType = (method as? java.lang.reflect.Method)?.returnType
                    if (returnType != null && returnType.name.contains("ParceledListSlice")) {
                        sliceOrNull(returnType, generated) ?: return@afterHook
                    } else {
                        generated
                    }
                }
                is List<*> -> generated
                is Array<*> -> generated.toTypedArray()
                else -> sliceOrNull(existing.javaClass, generated) ?: return@afterHook
            }
            if (FakeLoc.enableDebugLog) Logger.debug("getScanResults: ${generated.size} procedural APs for $packageName")
        })

        wifiClazz.hookAllMethods("getDhcpInfo", afterHook {
            val packageName = args.firstOrNull() as? String
            val rewrite = if (packageName != null) {
                shouldRewrite(packageName)
            } else {
                FakeLoc.enable && FakeLoc.enableMockWifi && Binder.getCallingUid() >= FIRST_APP_UID
            }
            if (!rewrite) return@afterHook
            result = buildDhcpInfo()
            if (FakeLoc.enableDebugLog) Logger.debug("getDhcpInfo: procedural DHCP info")
        })
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
        val timestamp = SystemClock.elapsedRealtimeNanos() / 1_000L
        return profile.wifi.map { ap ->
            ScanResult().apply {
                SSID = ap.ssid
                BSSID = ap.bssid
                capabilities = ap.capabilities
                frequency = ap.frequencyMhz
                level = ap.rssi
                this.timestamp = timestamp
                channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ
                centerFreq0 = ap.frequencyMhz
                centerFreq1 = 0
            }
        }
    }

    private fun buildWifiInfo(profile: EnvironmentProfile): WifiInfo? {
        val ap = profile.connectedWifi() ?: return null
        val info = runCatching { XposedHelpers.newInstance(WifiInfo::class.java) as WifiInfo }.getOrNull()
            ?: return null
        val values = listOf(
            "setBSSID" to ap.bssid,
            "setSSID" to ap.ssid,
            "setMacAddress" to DEVICE_MAC,
            "setNetworkId" to NETWORK_ID,
            "setRssi" to ap.rssi,
            "setFrequency" to ap.frequencyMhz,
            "setLinkSpeed" to LINK_SPEED_MBPS,
            "setIpAddress" to ipv4("192.168.1.100"),
            "setSupplicantState" to SupplicantState.COMPLETED,
        )
        values.forEach { (method, value) ->
            runCatching { XposedHelpers.callMethod(info, method, value) }
                .onFailure { if (FakeLoc.enableDebugLog) Logger.debug("WifiInfo.$method unavailable: ${it.javaClass.simpleName}") }
        }
        return info
    }

    private fun buildDhcpInfo(): DhcpInfo = DhcpInfo().apply {
        ipAddress = ipv4("192.168.1.100")
        gateway = ipv4("192.168.1.1")
        netmask = ipv4("255.255.255.0")
        dns1 = ipv4("192.168.1.1")
        dns2 = ipv4("223.5.5.5")
        serverAddress = gateway
        leaseDuration = 86_400
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

    private fun ipv4(address: String): Int {
        val parts = address.split('.')
        require(parts.size == 4)
        return parts.fold(0) { value, part -> (value shl 8) or part.toInt() }
    }

    private const val DEVICE_MAC = "A4:50:46:00:00:01"
    private const val NETWORK_ID = 42
    private const val LINK_SPEED_MBPS = 433
    private const val FIRST_APP_UID = 10_000
}
