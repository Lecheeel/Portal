@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package com.system.location.service.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.system.location.service.hook.hooks.LocationManagerHook
import com.system.location.service.hook.hooks.LocationServiceHook
import com.system.location.service.hook.hooks.fused.AndroidFusedLocationProviderHook
import com.system.location.service.hook.hooks.fused.ThirdPartyLocationHook
import com.system.location.service.hook.hooks.oplus.OplusLocationHook
import com.system.location.service.hook.hooks.telephony.miui.MiuiTelephonyManagerHook
import com.system.location.service.hook.hooks.sensor.SystemSensorManagerHook
import com.system.location.service.hook.hooks.telephony.TelephonyHook
import com.system.location.service.hook.hooks.wlan.WlanHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger

class FakeLocation: IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager
    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
        // the field is not guaranteed to exist
    }

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if(startupParam == null) return

    }

    /**
     * This method is called when an app is loaded. It's called very early, even before
     * [Application.onCreate] is called.
     * Modules can set up their app-specific hooks here.
     *
     * @param lpparam Information about the app.
     * @throws Throwable Everything the callback throws is caught and logged.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        lpparam ?: return
        val identity = com.system.location.service.hook.scope.ProcessIdentity(
            lpparam.packageName, lpparam.processName, lpparam.appInfo?.uid ?: android.os.Process.myUid(),
            android.os.Build.VERSION.SDK_INT, android.os.Build.MANUFACTURER,
        )
        // This callback is already restricted by the framework's explicitly selected scope.
        val targets = if (lpparam.packageName != "com.system.location.service" &&
            lpparam.appInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0)
            setOf(lpparam.packageName) else emptySet()
        val scope = com.system.location.service.hook.scope.HookScopeResolver.resolve(identity, targets)
        val status = com.system.location.service.hook.scope.HookStatusRegistry
        if (scope == null) {
            status.record("process:${identity.processName}", com.system.location.service.hook.scope.HookStatus(
                skipped = true, reason = "Outside supported process scope (SDK ${identity.sdk})"))
            return
        }
        status.record("process:${identity.processName}", com.system.location.service.hook.scope.HookStatus(supported = true))
        val loader = lpparam.classLoader
        fun install(name: String, action: () -> Unit) {
            runCatching(action).onFailure {
                status.record(name, com.system.location.service.hook.scope.HookStatus(supported = true,
                    failed = true, reason = it.javaClass.simpleName))
                Logger.error("Hook installation failed: $name", it)
            }
        }
        when (scope) {
            com.system.location.service.hook.scope.HookScope.SYSTEM_SERVER -> {
                FakeLoc.isSystemServerProcess = true
                install("system-location") { startFakeLocHook(loader) }
                install("telephony-registry") { TelephonyHook.hookSubOnTransact(loader) }
                install("wifi") { WlanHook(loader) }
                install("fused") { AndroidFusedLocationProviderHook(loader) }
                install("sensor") { SystemSensorManagerHook(loader) }
                install("system-sdk") { ThirdPartyLocationHook(loader) }
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
            com.system.location.service.hook.scope.HookScope.TARGET_APP -> {
                // App-specific IPC is intentionally unavailable until a caller allowlist is configured.
                status.record("target-app", com.system.location.service.hook.scope.HookStatus(
                    skipped = true, reason = "Target-app IPC unsupported; use the system location backend"))
            }
        }
    }
    private fun startFakeLocHook(classLoader: ClassLoader) {
        cServiceManager = XposedHelpers.findClass("android.os.ServiceManager", classLoader)

        XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
            TelephonyHook.hookTelephonyRegistry(it)
        } // for MUMU emulator

        val cLocationManager =
            XposedHelpers.findClass("android.location.LocationManager", classLoader)

        LocationServiceHook(classLoader)
        LocationManagerHook(cLocationManager)  // intrusive hooks
    }
}
