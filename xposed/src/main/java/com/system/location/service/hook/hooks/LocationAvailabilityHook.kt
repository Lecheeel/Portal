package com.system.location.service.hook.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.scope.HookStatus
import com.system.location.service.hook.scope.HookStatusRegistry
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.core.privacy.LocationAvailabilityPolicy

/**
 * P1-05 availability synthesis.
 *
 * Android 31–37 has no public `android.location.LocationAvailability` and AOSP 16
 * `ILocationManager`/`ILocationListener` expose no availability callback, so the only availability
 * surface a target app can observe is `isLocationEnabled*`. While a scenario is active, third-party
 * callers see `true`; system/Settings callers keep the real master-switch value.
 */
object LocationAvailabilityHook {
    fun hook(classLoader: ClassLoader): Boolean {
        val service = XposedHelpers.findClassIfExists(
            "com.android.server.location.LocationManagerService",
            classLoader,
        )
        if (service == null) {
            HookStatusRegistry.record("availability", HookStatus(
                supported = true, skipped = true, reason = "LocationManagerService absent"))
            return false
        }
        var hooked = false
        for (methodName in METHODS) {
            val hooks = HookInstaller.hookAllMethods(service, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    if (param == null || !FakeLoc.enable) return
                    if (!LocationAvailabilityPolicy.shouldSynthesize(true, BinderUtils.getCallerUid())) return
                    param.result = true
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("$methodName: synthesized true for third-party caller")
                    }
                }
            })
            hooked = hooked || hooks.isNotEmpty()
        }
        return hooked
    }

    private val METHODS = listOf("isLocationEnabledForUser", "isLocationEnabled")
}
