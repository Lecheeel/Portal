package com.system.location.service.hook.hooks.gnss

import android.location.Location
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation.invoke
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface

object GnssHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        hookFrameworkGnss(classLoader)
        hookHALGnss(classLoader)
    }

    private fun hookFrameworkGnss(classLoader: ClassLoader) {
        val cGnssManagerService =
            Reflection.findClassIfExists(
                "com.android.server.location.GnssManagerService",
                classLoader,
            ) ?: return

        val rawRegistration = rawGnssRegistrationHook()

        // A void registration reports intentional policy rejection by throwing. PROTECTIVE
        // would swallow that API result and execute the original registration instead.
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "addGnssAntennaInfoListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "addGnssMeasurementsListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "addGnssNavigationMessageListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "removeGnssAntennaInfoListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "removeGnssMeasurementsListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "removeGnssNavigationMessageListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
    }

    private fun hookHALGnss(classLoader: ClassLoader) {
        var hookedGnssLocationProvider = 0
        val cGnssLocationProviderImpl =
            Reflection.findClassIfExists(
                "com.android.server.location.gnss.GnssLocationProviderImpl",
                classLoader,
            )
        if (cGnssLocationProviderImpl != null) {
            BlindHookLocation(cGnssLocationProviderImpl, classLoader)
            hookedGnssLocationProvider = 1
        }

        if (hookedGnssLocationProvider == 0) {
            val cGnssLocationProvider =
                Reflection.findClassIfExists(
                    "com.android.server.location.gnss.GnssLocationProvider",
                    classLoader,
                )
            if (cGnssLocationProvider != null) {
                BlindHookLocation(cGnssLocationProvider, classLoader)
                hookedGnssLocationProvider = 2
            }
        }

        if (hookedGnssLocationProvider == 0) {
            Logger.error("Failed to find GnssLocationProvider!")
        } else {
            Logger.info("GnssLocationProvider done, status: $hookedGnssLocationProvider")
        }

        val cGnssNative =
            Reflection.findClassIfExists(
                "com.android.server.location.gnss.hal.GnssNative",
                classLoader,
            )
        if (cGnssNative != null) {
            BlindHookLocation(cGnssNative, classLoader)

            cGnssNative.hookAllMethods(
                "reportLocationBatch",
                XposedInterface.Hooker intercept38@{ chain ->
                    val args = chain.args.toTypedArray()

                    val locations = args.firstOrNull() as? Array<*>
                    if (FakeLoc.enable && locations != null &&
                        locations.javaClass.componentType == Location::class.java && locations.all { it is Location }) {
                        // The framework API takes a single Location[] argument. Copy the array:
                        // mutating the provider-owned batch can also affect other consumers.
                        args[0] = locations.map { injectLocation(it as Location) }.toTypedArray()
                    }

                    chain.proceed(args)
                },
            )
        } else {
            Logger.error("GnssNative not found")
        }
    }

}
