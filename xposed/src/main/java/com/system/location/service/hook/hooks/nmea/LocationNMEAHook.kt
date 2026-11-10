package com.system.location.service.hook.hooks.nmea

import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.utils.FakeLoc
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

        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssMeasurementsListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssMeasurementsListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssNavigationMessageListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssNavigationMessageListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "addGnssAntennaInfoListener",
            rawRegistration,
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            classILocationManager,
            "removeGnssAntennaInfoListener",
            rawRegistration,
        )
    }

    private fun hookGnssNmea(service: Class<*>) {
        service.hookAllMethods("registerGnssNmeaCallback", XposedInterface.Hooker registration@{ chain ->
            val args = chain.args
            val result = chain.proceed()
            if (result == false) return@registration result
            val callback = args.firstOrNull() as? IInterface ?: return@registration result
            installNmeaCallback(callback)
            NmeaListenerRouting.register(callback, args.getOrNull(1) as? String)
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

internal fun ownsNmeaDelivery(listener: Any?): Boolean =
    FakeLoc.enable && FakeLoc.enableMockGnss && FakeLoc.enableNMEA &&
        NmeaListenerRouting.shouldDeliver(listener) &&
        GnssReceiverRuntime.currentFrame(NmeaListenerRouting.fixedPointFor(listener)) != null
