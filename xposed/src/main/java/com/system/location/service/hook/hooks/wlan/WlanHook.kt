@file:Suppress("UNCHECKED_CAST", "PrivateApi")
package com.system.location.service.hook.hooks.wlan

import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
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
    private var cachedKey: WifiProfileCacheKey? = null
    private var cachedProfile: EnvironmentProfile? = null
    private var cachedFingerprintJson: String? = null
    private var cachedFingerprintSource: FingerprintWifiSource? = null
    private var cachedFingerprintEnabled: Boolean? = null
    private var fingerprintRevision = 0L

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

    fun installTargetApp(classLoader: ClassLoader, packageName: String, uid: Int) {
        val capabilities = Reflection.findClassIfExists("android.net.NetworkCapabilities", classLoader)
            ?: return
        capabilities.hookAllMethods("getTransportInfo", Hooker { chain ->
            val original = chain.proceed()
            if (original !is WifiInfo || !shouldRewrite(packageName, uid)) return@Hooker original
            val hasWifiTransport = runCatching {
                Reflection.callMethod(chain.thisObject, "hasTransport", NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)
            if (!hasWifiTransport || !isVisibleWifiInfo(original)) return@Hooker original
            val synthetic = runCatching { buildWifiInfo(currentProfile(packageName, uid)) }.getOrNull()
                ?: disconnectedWifiInfo()
            if (synthetic == null && FakeLoc.enableDebugLog) {
                Logger.warn("NetworkCapabilities.getTransportInfo: synthetic WifiInfo unavailable for $packageName")
            }
            synthetic
        })
    }

    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
        if (!FakeLoc.hookWifi) return

        wifiClazz.hookAllMethods("getConnectionInfo", Hooker { chain ->
            // Run the framework path first. It enforces the caller's location permission and
            // returns a redacted/disconnected WifiInfo when the caller may not see SSID/BSSID.
            val original = chain.proceed() as? WifiInfo ?: return@Hooker disconnectedWifiInfo()
            val packageName = chain.args.firstOrNull() as? String
            if (packageName.isNullOrEmpty() || !shouldRewrite(packageName)) return@Hooker original
            if (!isRealWifiConnected(original)) return@Hooker original
            // Never return null for WifiManager.getConnectionInfo(): the platform API is non-null
            // and a null here becomes an app-side NPE. A disconnected synthetic record is the
            // fail-closed value when a full profile cannot be constructed.
            val info = buildWifiInfo(currentProfile(packageName)) ?: disconnectedWifiInfo()
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
            if (generated.isEmpty()) return@Hooker emptyList<ScanResult>()
            val result = when (val existing = original) {
                null -> {
                    val returnType = (chain.executable as? java.lang.reflect.Method)?.returnType
                    if (returnType != null && returnType.name.contains("ParceledListSlice")) {
                        sliceOrNull(returnType, generated) ?: return@Hooker emptyList<ScanResult>()
                    } else {
                        generated
                    }
                }
                is List<*> -> generated
                is Array<*> -> generated.toTypedArray()
                // WifiManager.getScanResults() is non-null; an empty list is the fail-closed
                // value when the OEM return type cannot be constructed. Never leak the real list.
                else -> sliceOrNull(existing.javaClass, generated) ?: return@Hooker emptyList<ScanResult>()
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

    private fun shouldRewrite(packageName: String, uid: Int = BinderUtils.getCallerUid()): Boolean =
        FakeLoc.enable && FakeLoc.hookWifi && FakeLoc.enableMockWifi && !BinderUtils.isSystemPackages(packageName) &&
            ListenerRouting.decisionFor(packageName, uid) != com.system.location.service.core.routing.RoutingDecision.Deny

    private fun currentProfile(packageName: String? = null, uid: Int = BinderUtils.getCallerUid()): EnvironmentProfile {
        val snapshot = FakeLoc.runtimeSnapshots.current()
        val route = ListenerRouting.decisionFor(packageName, uid)
        val point = when (route) {
            com.system.location.service.core.routing.RoutingDecision.Deny -> Wgs84(snapshot.latitude, snapshot.longitude)
            is com.system.location.service.core.routing.RoutingDecision.Point -> route.point
            is com.system.location.service.core.routing.RoutingDecision.Scenario -> FakeLoc.snapshotForScenario(route.scenarioId).let {
                Wgs84(it.latitude, it.longitude)
            }
            com.system.location.service.core.routing.RoutingDecision.Global -> Wgs84(snapshot.latitude, snapshot.longitude)
        }
        val config = snapshot.configuration
        synchronized(profileLock) {
            refreshFingerprintSource(config.wifiFingerprintCatalogJson, config.wifiFingerprintEnabled)
            val key = WifiProfileCacheKey(
                point = point,
                seed = snapshot.environmentSeed,
                apCount = snapshot.environmentApCount,
                environmentVersion = snapshot.environmentVersion,
                sourceMode = snapshot.environmentSourceMode,
                fingerprintEnabled = config.wifiFingerprintEnabled,
                fingerprintRevision = fingerprintRevision,
            )
            cachedProfile?.let { if (cachedKey == key) return it }
            val now = System.currentTimeMillis()
            val fingerprint = fingerprintProfile(point, now, snapshot.environmentSeed, config.wifiFingerprintEnabled)
            val replay = if (snapshot.environmentSourceMode == EnvironmentSourceMode.PROCEDURAL) null else snapshot.environment
            val result = fingerprint
                ?: replay?.let { profile -> runCatching { ReplayEnvironmentSource(profile).profileAt(point, snapshot.environmentSeed, now) }.getOrNull() }
                    ?.takeIf { it.wifi.isNotEmpty() }
                ?: source(snapshot.environmentApCount).profileAt(point, snapshot.environmentSeed, now)
            return result.also {
                cachedKey = key
                cachedProfile = it
            }
        }
    }

    private fun refreshFingerprintSource(json: String?, enabled: Boolean) {
        if (cachedFingerprintJson == json && cachedFingerprintEnabled == enabled) return
        cachedFingerprintJson = json
        cachedFingerprintEnabled = enabled
        fingerprintRevision = Math.addExact(fingerprintRevision, 1L)
        cachedFingerprintSource = if (!enabled || json.isNullOrBlank()) null else {
            runCatching { FingerprintWifiSource(WifiFingerprintImporter.importJson(json).catalog.database()) }
                .onFailure { if (FakeLoc.enableDebugLog) Logger.debug("wifi fingerprint parse failed: ${it.message}") }
                .getOrNull()
        }
    }

    private fun fingerprintProfile(point: Wgs84, nowMillis: Long, seed: Long, enabled: Boolean): EnvironmentProfile? {
        if (!enabled) return null
        return cachedFingerprintSource?.profileAt(point, seed, nowMillis)
    }

    private fun source(apCount: Int): ProceduralWifiSource {
        if (sourceApCount != apCount) {
            source = ProceduralWifiSource(EnvironmentConfig(apPerCell = apCount))
            sourceApCount = apCount
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
                runCatching { Reflection.setIntField(this, "wifiStandard", ap.standard) }
            }
        }
    }

    private fun buildWifiInfo(profile: EnvironmentProfile): WifiInfo? {
        val ap = profile.connectedWifi() ?: profile.wifi.maxByOrNull { it.rssi } ?: return null
        val info = runCatching { Reflection.newInstance(WifiInfo::class.java) as WifiInfo }.getOrNull()
            ?: return null
        val linkSpeed = if (ap.frequencyMhz >= 5_000) 433 else 144
        val values: List<Pair<String, Any>> = listOf<Pair<String, Any>>(
            "setBSSID" to ap.bssid.lowercase(),
            "setSSID" to "\"${ap.ssid}\"",
            "setMacAddress" to syntheticWifiMac(FakeLoc.environmentSeed, ap.bssid),
            "setNetworkId" to (ap.bssid.lowercase().hashCode() and Int.MAX_VALUE),
            "setRssi" to ap.rssi,
            "setFrequency" to ap.frequencyMhz,
            "setLinkSpeed" to linkSpeed,
            "setRxLinkSpeedMbps" to linkSpeed,
            "setTxLinkSpeedMbps" to linkSpeed,
            "setMaxSupportedRxLinkSpeedMbps" to linkSpeed,
            "setMaxSupportedTxLinkSpeedMbps" to linkSpeed,
            "setWifiStandard" to ap.standard,
            "setIpAddress" to syntheticWifiIpv4(ap.bssid),
            "setSupplicantState" to SupplicantState.COMPLETED,
            "setHiddenSSID" to false,
        )
        values.forEach { (method, value) ->
            runCatching { Reflection.callMethod(info, method, value) }
                .onFailure { if (FakeLoc.enableDebugLog) Logger.debug("WifiInfo.$method unavailable: ${it.javaClass.simpleName}") }
        }
        return info
    }

    /**
     * Non-null, disconnected fallback that preserves the WifiInfo API contract without exposing
     * any real connection field. Used only when the synthetic profile cannot be constructed.
     */
    private fun disconnectedWifiInfo(): WifiInfo? = runCatching {
        Reflection.newInstance(WifiInfo::class.java) as WifiInfo
    }.getOrNull()?.apply {
        listOf<Pair<String, Any>>(
            "setSupplicantState" to SupplicantState.DISCONNECTED,
            "setSSID" to "<unknown ssid>",
            "setBSSID" to REDACTED_BSSID,
            "setNetworkId" to -1,
            "setIpAddress" to 0,
            "setMacAddress" to "02:00:00:00:00:00",
            "setRssi" to -127,
            "setFrequency" to 0,
            "setLinkSpeed" to 0,
        ).forEach { (method, value) ->
            runCatching { Reflection.callMethod(this, method, value) }
        }
    }

    private fun isVisibleWifiInfo(info: WifiInfo): Boolean =
        !info.ssid.isNullOrBlank() && !info.ssid.contains("unknown ssid", ignoreCase = true) &&
            !info.bssid.isNullOrBlank() && info.bssid != REDACTED_BSSID

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

internal fun syntheticWifiMac(seed: Long, bssid: String): String {
    val random = kotlin.random.Random(seed xor bssid.lowercase().hashCode().toLong())
    return buildString {
        append("02")
        repeat(5) { append(':').append("%02x".format(random.nextInt(256))) }
    }
}

/** WifiInfo stores IPv4 as a little-endian Int; keep this address synthetic and RFC1918. */
internal fun syntheticWifiIpv4(bssid: String): Int {
    val hash = bssid.lowercase().hashCode()
    val octets = intArrayOf(10, 64 + ((hash ushr 16) and 0x3f), (hash ushr 8) and 0xff, 2 + (hash and 0x7d))
    return octets[0] or (octets[1] shl 8) or (octets[2] shl 16) or (octets[3] shl 24)
}
