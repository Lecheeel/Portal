package com.system.location.service.hook.hooks

import com.system.location.service.core.privacy.LocationAvailabilityPolicy
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.scope.HookStatus
import com.system.location.service.hook.scope.HookStatusRegistry
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface

/**
 * While a scenario runs, third-party callers see location enabled; system callers retain the real
 * switch.
 */
object LocationAvailabilityHook {
    fun hook(classLoader: ClassLoader): Boolean {
        val service =
            Reflection.findClassIfExists(
                "com.android.server.location.LocationManagerService",
                classLoader,
            )
        if (service == null) {
            HookStatusRegistry.record(
                "availability",
                HookStatus(
                    supported = true,
                    skipped = true,
                    reason = "LocationManagerService absent",
                ),
            )
            return false
        }
        var hooked = false
        for (methodName in METHODS) {
            val hooks =
                HookInstaller.hookAllMethods(
                    service,
                    methodName,
                    XposedInterface.Hooker { chain ->
                        val original = chain.proceed()
                        // Never report a disabled platform switch as enabled: that is directly
                        // cross-checkable through Settings.Secure.location_mode.
                        if (original != true) return@Hooker original
                        if (
                            FakeLoc.enable &&
                                LocationAvailabilityPolicy.shouldSynthesize(
                                    true,
                                    BinderUtils.getCallerUid(),
                                )
                        ) {
                            if (FakeLoc.enableDebugLog)
                                Logger.debug("$methodName: synthesized true for third-party caller")
                            true
                        } else {
                            original
                        }
                    },
                )
            hooked = hooked || hooks.isNotEmpty()
        }
        return hooked
    }

    private val METHODS = listOf("isLocationEnabledForUser", "isLocationEnabled")
}
