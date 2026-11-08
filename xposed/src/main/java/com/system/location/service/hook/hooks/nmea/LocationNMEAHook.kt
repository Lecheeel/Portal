package com.system.location.service.hook.hooks.nmea

import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.onceHook
import com.system.location.service.hook.utils.onceHookMethod
import io.github.libxposed.api.XposedInterface
import java.util.Collections

object LocationNMEAHook : BaseLocationHook() {
    operator fun invoke(classILocationManager: Class<*>) {
        hookGnssNmea(classILocationManager)
        NmeaServiceHook.hook(classILocationManager.classLoader!!)

        val doNothingMethod =
            nmeaRegistrationHook(debugLog = { if (FakeLoc.enableDebugLog) Logger.debug(it) })

        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssMeasurementsListener",
            doNothingMethod,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssMeasurementsListener",
            doNothingMethod,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssNavigationMessageListener",
            doNothingMethod,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssNavigationMessageListener",
            doNothingMethod,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssAntennaInfoListener",
            doNothingMethod,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssAntennaInfoListener",
            doNothingMethod,
        )
    }

    private fun hookGnssNmea(classILocationManager: Class<*>) {
        val hookedGnssCallback = Collections.synchronizedSet(HashSet<String>())
        val counters = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val unhooks =
            classILocationManager.declaredMethods
                .filter { it.name == "registerGnssNmeaCallback" && it.parameterTypes.size > 1 }
                .map { method ->
                    method.onceHook(
                        XposedInterface.Hooker intercept65@{ chain ->
                            val args = chain.args.toTypedArray()

                            val callback = args[0] ?: return@intercept65 chain.proceed(args)
                            val cIGnssNmeaCallback = callback.javaClass
                            if (callback is android.os.IInterface) {
                                NmeaListenerRouting.register(callback, args.getOrNull(1) as? String)
                            }

                            if (hookedGnssCallback.contains(cIGnssNmeaCallback.name))
                                return@intercept65 chain.proceed(args)
                            hookedGnssCallback.add(cIGnssNmeaCallback.name)

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("registerGnssNmeaCallback: $cIGnssNmeaCallback")
                            }

                            cIGnssNmeaCallback.onceHookMethod(
                                "onNmeaReceived",
                                Long::class.java,
                                String::class.java,
                                callback =
                                    XposedInterface.Hooker intercept66@{ chain ->
                                        val args = chain.args.toTypedArray()

                                        if (!FakeLoc.enable) return@intercept66 chain.proceed(args)
                                        // Simulation owns NMEA delivery. Do not both rewrite the
                                        // real stream and inject generated frames: that produces
                                        // duplicate/partial bursts and invalid GSV numbering.
                                        return@intercept66 null
                                    },
                            )

                            chain.proceed(args)
                        }
                    )
                }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("found ${unhooks.size} registerGnssNmeaCallback")
        }
    }
}

/** Suppress new raw listeners while keeping Android responsible for unregistering existing ones. */
internal fun nmeaRegistrationHook(
    enable: () -> Boolean = { FakeLoc.enable },
    enableNmea: () -> Boolean = { FakeLoc.enableNMEA },
    debugLog: (String) -> Unit = {},
): XposedInterface.Hooker =
    XposedInterface.Hooker { chain ->
        if (chain.args.isEmpty()) return@Hooker chain.proceed()
        val method = chain.executable.name
        debugLog("doNothingMethod: $method")
        if (method.startsWith("add") && enable() && !enableNmea()) {
            debugLog("$method: disable")
            null
        } else {
            chain.proceed()
        }
    }
