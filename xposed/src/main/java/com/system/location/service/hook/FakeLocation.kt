@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package com.system.location.service.hook

import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.hooks.LocationManagerHook
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.hooks.fused.AndroidFusedLocationProviderHook
import com.system.location.service.hook.hooks.fused.ThirdPartyLocationHook
import com.system.location.service.hook.hooks.oplus.OplusLocationHook
import com.system.location.service.hook.hooks.telephony.miui.MiuiTelephonyManagerHook
import com.system.location.service.hook.hooks.sensor.SystemSensorManagerHook
import com.system.location.service.hook.hooks.telephony.TelephonyHook
import com.system.location.service.hook.hooks.wlan.WlanHook
import com.system.location.service.hook.hooks.LocationAvailabilityHook
import com.system.location.service.hook.hooks.provider.ProviderRegistryHook
import com.system.location.service.hook.hooks.pkg.PackageMetadataHook
import com.system.location.service.hook.privacy.AppOpsHook
import com.system.location.service.hook.privacy.SettingsHook
import com.system.location.service.hook.privacy.StackTraceCleanHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger

class FakeLocation {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager
    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
        // the field is not guaranteed to exist
    }

    /**
     * Entry point shared by the modern libxposed callbacks.
     */
    fun install(
        processName: String,
        packageName: String,
        classLoader: ClassLoader,
        uid: Int,
        appFlags: Int = 0,
    ) {
        val identity = com.system.location.service.hook.scope.ProcessIdentity(
            packageName, processName, uid,
            android.os.Build.VERSION.SDK_INT, android.os.Build.MANUFACTURER,
        )
        // This callback is already restricted by the framework's explicitly selected scope.
        val targets = if (packageName != "com.system.location.service" &&
            appFlags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0)
            setOf(packageName) else emptySet()
        val scope = com.system.location.service.hook.scope.HookScopeResolver.resolve(identity, targets)
        val status = com.system.location.service.hook.scope.HookStatusRegistry
        android.util.Log.i("PortalModule", "install process=$processName package=$packageName uid=$uid scope=$scope hooks=${status.snapshot().size}")
        com.system.location.service.hook.runtime.HookRuntime.log("install process=$processName package=$packageName uid=$uid scope=$scope hooks=${status.snapshot().size}")
        if (scope == null) {
            status.record("process:${identity.processName}", com.system.location.service.hook.scope.HookStatus(
                skipped = true, reason = "Outside supported process scope (SDK ${identity.sdk})"))
            return
        }
        status.recordScope(scope)
        status.record("process:${identity.processName}", com.system.location.service.hook.scope.HookStatus(supported = true))
        val loader = classLoader
        fun install(name: String, action: () -> Unit) {
            runCatching(action).onFailure {
                status.record(name, com.system.location.service.hook.scope.HookStatus(supported = true,
                    failed = true, reason = it.javaClass.simpleName))
                Logger.error("Hook installation failed: $name", it)
            }
        }
        // Opt-in and runtime-gated: install in every scope so a later configuration change takes
        // effect without a process restart; the hook body checks the flag on every call.
        install("stack-trace-clean") { StackTraceCleanHook.install(loader) }
        when (scope) {
            com.system.location.service.hook.scope.HookScope.SYSTEM_SERVER -> {
                FakeLoc.isSystemServerProcess = true
                install("system-location") { startFakeLocHook(loader) }
                install("appops") { AppOpsHook.hook(loader) }
                install("provider-registry") { ProviderRegistryHook.hook(loader) }
                install("availability") { LocationAvailabilityHook.hook(loader) }
                install("telephony-registry") { TelephonyHook.hookSubOnTransact(loader) }
                install("wifi") { WlanHook(loader) }
                install("fused") { AndroidFusedLocationProviderHook(loader) }
                install("sensor") { SystemSensorManagerHook(loader) }
                install("system-sdk") { ThirdPartyLocationHook(loader) }
                // Some ROMs host SettingsProvider inside system_server; the Settings-provider
                // scope still handles the separate-process case.
                install("settings") { SettingsHook.hook(loader) }
                install("package-metadata") { PackageMetadataHook.hook(loader) }
            }
            com.system.location.service.hook.scope.HookScope.PHONE_PROCESS -> {
                install("phone") { TelephonyHook(loader) }
            }
            com.system.location.service.hook.scope.HookScope.FUSED_PROCESS -> {
                install("fused") { AndroidFusedLocationProviderHook(loader) }
            }
            com.system.location.service.hook.scope.HookScope.VENDOR_LOCATION_PROCESS -> {
                if (identity.packageName == "com.oplus.location") {
                    install("oplus") { OplusLocationHook(loader) }
                } else {
                    install("xiaomi") { ThirdPartyLocationHook(loader) }
                }
            }
            com.system.location.service.hook.scope.HookScope.SETTINGS_PROVIDER -> {
                install("settings") { SettingsHook.hook(loader) }
            }
            com.system.location.service.hook.scope.HookScope.TARGET_APP -> {
                install("application-sensor") {
                    com.system.location.service.hook.hooks.sensor.ApplicationSensorHook.install(loader)
                }
                install("application-anti-detect") {
                    com.system.location.service.hook.antidetect.AntiDetectController.start()
                }
            }
        }
    }
    private fun startFakeLocHook(classLoader: ClassLoader) {
        cServiceManager = Reflection.findClass("android.os.ServiceManager", classLoader)

        Reflection.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
            TelephonyHook.hookTelephonyRegistry(it)
        } // for MUMU emulator

        val cLocationManager =
            Reflection.findClass("android.location.LocationManager", classLoader)

        LocationServiceHook(classLoader)
        LocationManagerHook(cLocationManager)  // intrusive hooks
    }
}
