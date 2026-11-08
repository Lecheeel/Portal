package com.system.location.service.hook.hooks.nmea

import android.os.IInterface
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 14 may route addNmeaListener through GnssManagerService instead of ILocationManager.
 * Register those callbacks with the same delivery path so simulation is not transport-dependent.
 */
object NmeaServiceHook {
    private val hookedCallbacks = ConcurrentHashMap.newKeySet<Class<*>>()

    fun hook(classLoader: ClassLoader) {
        val service = Reflection.findClassIfExists(
            "com.android.server.location.GnssManagerService",
            classLoader,
        ) ?: return
        val methods = service.declaredMethods.filter { method ->
            val name = method.name
            name.contains("Nmea", ignoreCase = true) &&
                (name.startsWith("register") || name.startsWith("add"))
        }
        if (methods.isEmpty()) {
            HookRuntime.log("GnssManagerService NMEA registration methods not found")
            return
        }
        methods.forEach { method ->
            HookInstaller.hookMethod(method, Hooker { chain ->
                val args = chain.args
                (args.firstOrNull() as? IInterface)?.let { callback ->
                    NmeaListenerRouting.register(callback, args.getOrNull(1) as? String)
                    hookCallback(callback)
                }
                chain.proceed()
            })
        }
        HookRuntime.log("GnssManagerService NMEA hook installed methods=${methods.size}")
    }

    private fun hookCallback(callback: IInterface) {
        val type = callback.javaClass
        if (!hookedCallbacks.add(type)) return
        val method = type.declaredMethods.firstOrNull { it.name == "onNmeaReceived" } ?: return
        HookInstaller.hookMethod(method, Hooker { chain ->
            // Simulation delivery is owned by GnssSignalInjection; drop the real stream.
            if (FakeLoc.enable) null else chain.proceed()
        })
    }
}
