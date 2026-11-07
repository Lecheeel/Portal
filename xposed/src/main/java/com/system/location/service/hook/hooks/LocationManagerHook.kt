package com.system.location.service.hook.hooks

import android.location.Location
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.hookMethod
import com.system.location.service.hook.utils.onceHookAllMethod
import io.github.libxposed.api.XposedInterface

object LocationManagerHook : BaseLocationHook() {
    operator fun invoke(cLocationManager: Class<*>) {
        val hookGetLastKnownLocation =
            XposedInterface.Hooker intercept4@{ chain ->
                val args = chain.args.toTypedArray()
                var result = chain.proceed()

                if (result == null) return@intercept4 result

                if (!FakeLoc.enable) return@intercept4 result

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${chain.executable.name}: injected!")
                }

                result = injectLocation(result as Location)

                result
            }
        if (
            cLocationManager.declaredMethods
                .filter { it.name == "getLastKnownLocation" && it.parameterTypes.size > 1 }
                .map {
                    com.system.location.service.hook.scope.HookInstaller.hookMethod(
                        it,
                        hookGetLastKnownLocation,
                    )
                }
                .isEmpty()
        ) {
            com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
                cLocationManager,
                "getLastLocation",
                hookGetLastKnownLocation,
            )
        }

        val hookOnLocation =
            XposedInterface.Hooker intercept5@{ chain ->
                val args = chain.args.toTypedArray()

                if (args.isEmpty() || args[0] == null) return@intercept5 chain.proceed(args)

                if (!FakeLoc.enable) return@intercept5 chain.proceed(args)

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${chain.executable.name}: injected!")
                }

                when (args[0]) {
                    is Location -> {
                        args[0] = injectLocation(args[0] as Location)
                    }
                    is List<*> -> {
                        val locations = args[0] as List<*>
                        args[0] = locations.map { injectLocation(it as Location) }
                    }
                    else -> {
                        Logger.error("Unknown method when hook hookOnLocation: ${chain.executable}")
                    }
                }

                chain.proceed(args)
            }

        if (
            cLocationManager.declaredMethods
                .filter { it.name == "requestFlush" }
                .map {
                    com.system.location.service.hook.scope.HookInstaller.hookMethod(
                        it,
                        XposedInterface.Hooker intercept6@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (args.size < 2 || args[1] == null)
                                return@intercept6 chain.proceed(args)

                            val listener = args[1] ?: return@intercept6 chain.proceed(args)
                            listener.javaClass.onceHookAllMethod(
                                "onLocationChanged",
                                hookOnLocation,
                            )

                            chain.proceed(args)
                        },
                    )
                }
                .isEmpty()
        ) {
            Logger.error("Hook requestFlush failed")
        }

        val hookRequestLocationUpdates =
            XposedInterface.Hooker intercept7@{ chain ->
                val args = chain.args.toTypedArray()

                if (args.size < 2 || args[1] == null) return@intercept7 chain.proceed(args)

                args
                    .filterIsInstance<android.location.LocationListener>()
                    .also {
                        if (it.isEmpty()) {
                            Logger.error(
                                "No LocationListener found in requestLocationUpdates: ${chain.executable}(${
                            args.joinToString { it?.javaClass.toString() }
                        })"
                            )
                        }
                    }
                    .forEach { it.javaClass.onceHookAllMethod("onLocationChanged", hookOnLocation) }

                chain.proceed(args)
            }
        if (
            cLocationManager.declaredMethods
                .filter { it.name == "requestLocationUpdates" }
                .map {
                    com.system.location.service.hook.scope.HookInstaller.hookMethod(
                        it,
                        hookRequestLocationUpdates,
                    )
                }
                .isEmpty()
        ) {
            Logger.error("Hook requestLocationUpdates failed")
        }

        if (
            cLocationManager.declaredMethods
                .filter { it.name == "requestSingleUpdate" }
                .map {
                    com.system.location.service.hook.scope.HookInstaller.hookMethod(
                        it,
                        hookRequestLocationUpdates,
                    )
                }
                .isEmpty()
        ) {
            Logger.error("Hook requestSingleUpdate failed")
        }

        kotlin
            .runCatching {
                Reflection.findClass(
                    "android.location.LocationManager\$GetCurrentLocationTransport",
                    cLocationManager.classLoader,
                )
            }
            .onSuccess { it.onceHookAllMethod("onLocation", hookOnLocation) }
            .onFailure { HookRuntime.log(it) }

        kotlin
            .runCatching {
                Reflection.findClass(
                    "android.location.LocationManager\$BatchedLocationCallbackWrapper",
                    cLocationManager.classLoader,
                )
            }
            .onSuccess { it.onceHookAllMethod("onLocationChanged", hookOnLocation) }

        kotlin
            .runCatching {
                Reflection.findClass(
                    "android.location.LocationManager\$LocationListenerTransport",
                    cLocationManager.classLoader,
                )
            }
            .onSuccess { it.onceHookAllMethod("onLocationChanged", hookOnLocation) }
            .onFailure { HookRuntime.log(it) }
    }
}
