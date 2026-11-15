package com.system.location.service.hook.hooks.nmea

import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.onceHookAllMethod
import io.github.libxposed.api.XposedInterface
import android.os.IInterface
import com.system.location.service.hook.hooks.gnss.GnssReceiverRuntime
import com.system.location.service.hook.hooks.gnss.rawGnssRegistrationHook

object LocationNMEAHook : BaseLocationHook() {
    operator fun invoke(classILocationManager: Class<*>) {
        hookGnssNmea(classILocationManager)
        NmeaServiceHook.hook(classILocationManager.classLoader!!)

        val rawRegistration = rawGnssRegistrationHook()

        // Preserve explicit unsupported results from void raw-GNSS registration methods.
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssMeasurementsListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssMeasurementsListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssNavigationMessageListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssNavigationMessageListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssAntennaInfoListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssAntennaInfoListener",
            rawRegistration,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
    }

    private fun hookGnssNmea(service: Class<*>) {
        service.hookAllMethods("registerGnssNmeaCallback", XposedInterface.Hooker registration@{ chain ->
            val args = chain.args
            val callback = args.firstOrNull() as? IInterface
            // Arm the payload guard before the real registration can emit its first sentence.
            if (callback != null) {
                installNmeaCallback(callback)
                NmeaListenerRouting.register(callback, args.getOrNull(1) as? String)
            }
            val result = try {
                chain.proceed()
            } catch (failure: Throwable) {
                callback?.let(NmeaListenerRouting::unregister)
                throw failure
            }
            if (result == false) callback?.let(NmeaListenerRouting::unregister)
            result
        })
        service.hookAllMethods("unregisterGnssNmeaCallback", XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            if (result != false) (chain.args.firstOrNull() as? IInterface)?.let(NmeaListenerRouting::unregister)
            result
        })
    }
}

internal fun installNmeaCallback(callback: IInterface) {
    callback.javaClass.onceHookAllMethod("onNmeaReceived", XposedInterface.Hooker { chain ->
        val types = chain.executable.parameterTypes.toList()
        val supported = types == listOf(Long::class.javaPrimitiveType, String::class.java)
        if (supported && ownsNmeaDelivery(chain.thisObject)) null else chain.proceed()
    })
}

/**
 * Protection is independent of synthetic NMEA availability. `nmeaEnabled` controls whether we
 * generate sentences; it must never turn a protected listener back into a real-NMEA passthrough.
 * A missing frame likewise suppresses the real sentence instead of forwarding it.
 */
internal fun ownsNmeaDelivery(listener: Any?): Boolean {
    val context = GnssReceiverRuntime.protectionContext() ?: return false
    return NmeaListenerRouting.routeFor(listener, context.state.routing) != null
}
