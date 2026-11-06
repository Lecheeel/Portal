package com.system.location.service.hook.hooks.nmea

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.onceHookAllMethod
import com.system.location.service.hook.utils.onceHookBefore
import com.system.location.service.hook.utils.onceHookMethodBefore
import java.util.Collections

object LocationNMEAHook: BaseLocationHook() {
    operator fun invoke(classILocationManager: Class<*>) {
        hookGnssNmea(classILocationManager)

        val doNothingMethod = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty()) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("doNothingMethod: ${param.method.name}")
                }

                if (FakeLoc.enable && !FakeLoc.enableNMEA) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method.name}: disable")
                    }
                    param.result = null
                }
            }
        }

        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(classILocationManager, "addGnssMeasurementsListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(classILocationManager, "removeGnssMeasurementsListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(classILocationManager, "addGnssNavigationMessageListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(classILocationManager, "removeGnssNavigationMessageListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(classILocationManager, "addGnssAntennaInfoListener", doNothingMethod)
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(classILocationManager, "removeGnssAntennaInfoListener", doNothingMethod)
    }

    private fun hookGnssNmea(classILocationManager: Class<*>) {
        val hookedGnssCallback = Collections.synchronizedSet(HashSet<String>())
        val unhooks = classILocationManager.declaredMethods.filter {
            it.name == "registerGnssNmeaCallback" && it.parameterTypes.size > 1
        }.map { method ->
            method.onceHookBefore {
                val cIGnssNmeaCallback = (args[0] ?: return@onceHookBefore).javaClass

                if (hookedGnssCallback.contains(cIGnssNmeaCallback.name)) return@onceHookBefore
                hookedGnssCallback.add(cIGnssNmeaCallback.name)

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("registerGnssNmeaCallback: $cIGnssNmeaCallback")
                }

                cIGnssNmeaCallback.onceHookMethodBefore("onNmeaReceived", Long::class.java, String::class.java) {
                    // Single NMEA rewrite point (P3-04). When simulation is active but the NMEA
                    // feature is off, suppress the real sentences instead of leaking them; when on,
                    // rewrite them and suppress anything the current generator cannot map yet.
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    val nmea = args[1] as? String ?: return@onceHookMethodBefore
                    if (!FakeLoc.enableNMEA) {
                        args[1] = ""
                        return@onceHookMethodBefore
                    }
                    args[1] = injectNMEA(nmea) ?: ""
                }
            }
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("found ${unhooks.size} registerGnssNmeaCallback")
        }
    }
}
