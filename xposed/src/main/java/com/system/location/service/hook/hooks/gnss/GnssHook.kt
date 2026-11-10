package com.system.location.service.hook.hooks.gnss

import android.location.Location
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation.invoke
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.onceHookAllMethod
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

        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "addGnssAntennaInfoListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "addGnssMeasurementsListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "addGnssNavigationMessageListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "removeGnssAntennaInfoListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "removeGnssMeasurementsListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cGnssManagerService,
            "removeGnssNavigationMessageListener",
            rawRegistration,
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

                    if (FakeLoc.enable && args.size > 1 && args[0] is Array<*>) {
                        val locations = args[0] as Array<Location>
                        locations.forEachIndexed { index, location ->
                            locations[index] = injectLocation(location)
                        }
                    }

                    chain.proceed(args)
                },
            )
        } else {
            Logger.error("GnssNative not found")
        }
    }

    private fun hookGnssHALAnyVersion(cIGnssStub: Class<*>?) {
        if (cIGnssStub != null) {
            cIGnssStub.hookAllMethods(
                "onTransact",
                XposedInterface.Hooker intercept39@{ chain ->
                    val args = chain.args.toTypedArray()

                    val clazz =
                        chain.thisObject?.javaClass ?: return@intercept39 chain.proceed(args)
                    clazz.onceHookAllMethod(
                        "injectBestLocation",
                        XposedInterface.Hooker intercept40@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("injectLocation: ${chain.executable}")
                            }
                            if (FakeLoc.enable) return@intercept40 null

                            chain.proceed(args)
                        },
                    )

                    clazz.onceHookAllMethod(
                        "injectLocation",
                        XposedInterface.Hooker intercept41@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("injectLocation: ${chain.executable}")
                            }
                            if (FakeLoc.enable) return@intercept41 null

                            chain.proceed(args)
                        },
                    )

                    chain.proceed(args)
                },
            )
        } else {
            Logger.error("IGnss\$Stub not found")
        }
    }
}
