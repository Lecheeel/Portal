package com.system.location.service.hook.hooks.provider

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.scope.HookStatus
import com.system.location.service.hook.scope.HookStatusRegistry
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger

/**
 * Keeps Portal's private control provider and test providers out of the public provider registry
 * (P1-04).
 *
 * The control channel itself is not a real LocationProvider; it is handled by the
 * `sendExtraCommand` / `isProviderEnabledForUser` hooks. This class only sanitizes the registry
 * read APIs that third-party apps can call:
 *
 * - `getAllProviders` / `getProviders` filter `fused_ext` and test/mock/portal providers;
 * - `hasProvider` reports them absent;
 * - `getProviderProperties` throws the same IllegalArgumentException a missing provider would.
 */
object ProviderRegistryHook {
    fun hook(classLoader: ClassLoader): Boolean {
        val service = XposedHelpers.findClassIfExists(
            "com.android.server.location.LocationManagerService",
            classLoader,
        )
        if (service == null) {
            HookStatusRegistry.record("provider-registry", HookStatus(
                supported = true, skipped = true, reason = "LocationManagerService absent"))
            return false
        }
        return hook(service)
    }

    fun hook(locationManagerService: Class<*>): Boolean {
        val filterHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = when (val value = param.result) {
                    is List<*> -> value.filterNot { it is String && isSuspiciousProvider(it) }
                    is Array<*> -> value.filterNot { it is String && isSuspiciousProvider(it) }.toTypedArray()
                    else -> value
                }
            }
        }
        val hasProviderHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val provider = param.args.firstOrNull() as? String ?: return
                if (isSuspiciousProvider(provider)) param.result = false
            }
        }
        val propertiesHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val provider = param.args.firstOrNull() as? String ?: return
                if (!isSuspiciousProvider(provider)) return
                param.setThrowable(IllegalArgumentException("provider \"$provider\" does not exist"))
                if (FakeLoc.enableDebugLog) Logger.debug("ProviderRegistry hid properties for $provider")
            }
        }

        var hooked = false
        for (name in LIST_METHODS) {
            hooked = HookInstaller.hookAllMethods(locationManagerService, name, filterHook).isNotEmpty() || hooked
        }
        hooked = HookInstaller.hookAllMethods(locationManagerService, "hasProvider", hasProviderHook).isNotEmpty() || hooked
        hooked = HookInstaller.hookAllMethods(locationManagerService, "getProviderProperties", propertiesHook).isNotEmpty() || hooked
        return hooked
    }

    private fun isSuspiciousProvider(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("fused_ext") ||
            lower.contains("test") ||
            lower.contains("mock") ||
            lower.contains("portal")
    }

    private val LIST_METHODS = listOf("getAllProviders", "getProviders")
}
