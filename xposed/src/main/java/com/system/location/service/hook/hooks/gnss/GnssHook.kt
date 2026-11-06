package com.system.location.service.hook.hooks.gnss

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation.invoke
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.beforeHook
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.onceHookAllMethod

object GnssHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        hookFrameworkGnss(classLoader)
        hookHALGnss(classLoader)
    }

    private fun hookFrameworkGnss(classLoader: ClassLoader) {
        val cGnssManagerService = XposedHelpers.findClassIfExists("com.android.server.location.GnssManagerService", classLoader) ?: return

        val doNothingMethod = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty()) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("doNothingMethod: ${param.method.name}")
                }

                // Suppress only registration while raw output is hidden. Suppressing remove*
                // would leak any listener that was registered before the scenario started.
                if (FakeLoc.enable && FakeLoc.enableMockGnss && !FakeLoc.enableAGPS &&
                    param.method.name.startsWith("add")) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method.name}: disable")
                    }
                    param.result = null
                }
            }
        }

        // AGPS Listener?
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(cGnssManagerService, "addGnssAntennaInfoListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(cGnssManagerService, "addGnssMeasurementsListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(cGnssManagerService, "addGnssNavigationMessageListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(cGnssManagerService, "removeGnssAntennaInfoListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(cGnssManagerService, "removeGnssMeasurementsListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(cGnssManagerService, "removeGnssNavigationMessageListener", doNothingMethod)

    }

    private fun hookHALGnss(classLoader: ClassLoader) {
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V1_0.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V1_1.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V2_1.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V2_0.IGnss\$Stub", classLoader))
        var hookedGnssLocationProvider = 0
        val cGnssLocationProviderImpl = XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssLocationProviderImpl", classLoader)
        if (cGnssLocationProviderImpl != null) {
            BlindHookLocation(cGnssLocationProviderImpl, classLoader)
            hookedGnssLocationProvider = 1
        }

        if (hookedGnssLocationProvider == 0) {
            val cGnssLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssLocationProvider", classLoader)
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

        val cGnssNative = XposedHelpers.findClassIfExists("com.android.server.location.gnss.hal.GnssNative", classLoader)
        if (cGnssNative != null) {
            BlindHookLocation(cGnssNative, classLoader)

            cGnssNative.hookAllMethods("reportLocationBatch", beforeHook {
                if (FakeLoc.enable && args.size > 1 && args[0] is Array<*>) {
                    val locations = args[0] as Array<Location>
                    locations.forEachIndexed { index, location ->
                        locations[index] = injectLocation(location)
                    }
                }
            })
        } else {
            Logger.error("GnssNative not found")
        }
    }

    private fun hookGnssHALAnyVersion(cIGnssStub: Class<*>?) {
        if (cIGnssStub != null) {
            cIGnssStub.hookAllMethods("onTransact", beforeHook {
                val clazz = thisObject.javaClass
                clazz.onceHookAllMethod("injectBestLocation", beforeHook {
                    if (FakeLoc.enable) {
                        result = Unit
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("injectLocation: $method")
                    }
                })

                clazz.onceHookAllMethod("injectLocation", beforeHook {
                    if (FakeLoc.enable) {
                        result = Unit
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("injectLocation: $method")
                    }
                })
            })
        } else {
            Logger.error("IGnss\$Stub not found")
        }
    }
}
