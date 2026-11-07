package com.system.location.service.hook.hooks.provider

import com.system.location.service.core.providers.ProviderRegistryPolicy
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.scope.HookStatus
import com.system.location.service.hook.scope.HookStatusRegistry
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface

/** Keeps Portal's control and test providers out of the public provider registry. */
object ProviderRegistryHook {
    fun hook(classLoader: ClassLoader): Boolean {
        val service =
            Reflection.findClassIfExists(
                "com.android.server.location.LocationManagerService",
                classLoader,
            )
        if (service == null) {
            HookStatusRegistry.record(
                "provider-registry",
                HookStatus(
                    supported = true,
                    skipped = true,
                    reason = "LocationManagerService absent",
                ),
            )
            return false
        }
        return hook(service)
    }

    fun hook(locationManagerService: Class<*>): Boolean {
        val filterHook =
            XposedInterface.Hooker { chain ->
                when (val value = chain.proceed()) {
                    is List<*> -> value.filterNot { it is String && isSuspiciousProvider(it) }
                    is Array<*> ->
                        value.filterNot { it is String && isSuspiciousProvider(it) }.toTypedArray()
                    else -> value
                }
            }
        val hasProviderHook =
            XposedInterface.Hooker { chain ->
                val provider = chain.args.firstOrNull() as? String
                if (provider != null && isSuspiciousProvider(provider)) false else chain.proceed()
            }
        val propertiesHook =
            XposedInterface.Hooker { chain ->
                val provider = chain.args.firstOrNull() as? String
                if (provider != null && isSuspiciousProvider(provider)) {
                    if (FakeLoc.enableDebugLog)
                        Logger.debug("ProviderRegistry hid properties for $provider")
                    throw IllegalArgumentException("provider \"$provider\" does not exist")
                }
                chain.proceed()
            }

        var hooked = false
        for (name in LIST_METHODS) {
            hooked =
                HookInstaller.hookAllMethods(locationManagerService, name, filterHook)
                    .isNotEmpty() || hooked
        }
        hooked =
            HookInstaller.hookAllMethods(locationManagerService, "hasProvider", hasProviderHook)
                .isNotEmpty() || hooked
        // This exception is an intentional API result; the framework must propagate it to the
        // caller.
        hooked =
            HookInstaller.hookAllMethods(
                    locationManagerService,
                    "getProviderProperties",
                    propertiesHook,
                    exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
                )
                .isNotEmpty() || hooked
        return hooked
    }

    private fun isSuspiciousProvider(name: String): Boolean = ProviderRegistryPolicy.isHidden(name)

    private val LIST_METHODS = listOf("getAllProviders", "getProviders")
}
