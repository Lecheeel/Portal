package com.system.location.service.hook.hooks.nmea

import android.os.IInterface
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import io.github.libxposed.api.XposedInterface.Hooker

/** Android may register NMEA directly through GnssManagerService. Both transports share ownership. */
object NmeaServiceHook {
    fun hook(classLoader: ClassLoader) {
        val service = Reflection.findClassIfExists(
            "com.android.server.location.GnssManagerService", classLoader,
        ) ?: return
        val methods = service.declaredMethods.filter { method ->
            val name = method.name
            name.contains("Nmea", ignoreCase = true) &&
                listOf("register", "add", "unregister", "remove").any(name::startsWith)
        }
        methods.forEach { method ->
            val registering = method.name.startsWith("register") || method.name.startsWith("add")
            HookInstaller.hookMethod(method, Hooker { chain ->
                val args = chain.args
                val callback = args.firstOrNull() as? IInterface
                // The real registration may synchronously emit a sentence. Arm the guard and
                // routing first; roll both back if the framework rejects the registration.
                if (registering && callback != null) {
                    installNmeaCallback(callback)
                    NmeaListenerRouting.register(callback, args.getOrNull(1) as? String)
                }
                val result = try {
                    chain.proceed()
                } catch (failure: Throwable) {
                    if (registering) callback?.let(NmeaListenerRouting::unregister)
                    throw failure
                }
                if (!registering && result != false) callback?.let(NmeaListenerRouting::unregister)
                if (registering && result == false) callback?.let(NmeaListenerRouting::unregister)
                result
            })
        }
        HookRuntime.log("GnssManagerService NMEA hooks installed methods=${methods.size}")
    }
}
