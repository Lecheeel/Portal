@file:Suppress("KotlinConstantConditions")
@file:OptIn(ExperimentalUuidApi::class)

package com.system.location.service.hook.hooks

import android.location.Location
import android.os.Bundle
import android.os.IInterface
import android.os.Parcel
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.RemoteCommandHandler
import com.system.location.service.hook.hooks.gnss.GnssHook
import com.system.location.service.hook.hooks.gnss.GnssListenerRouting
import com.system.location.service.hook.hooks.gnss.GnssReceiverRuntime
import com.system.location.service.hook.hooks.gnss.ReceiverGnssStatusAdapter
import com.system.location.service.hook.hooks.miui.MiuiBlurLocationProviderHook
import com.system.location.service.hook.hooks.miui.MiuiLocationManagerHook
import com.system.location.service.hook.hooks.nmea.LocationNMEAHook
import com.system.location.service.hook.hooks.provider.LocationProviderManagerHook
import com.system.location.service.hook.hooks.telephony.miui.MiuiTelephonyManagerHook
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.hookMethod
import com.system.location.service.hook.utils.onceHookAllMethod
import com.system.location.service.hook.utils.ProviderView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.ExperimentalUuidApi

internal object LocationServiceHook : BaseLocationHook() {
    private val listeners = LocationCallbackRegistry(
        added = { listener -> hookILocationListener(listener); ListenerRouting.register(listener) },
        removed = ListenerRouting::unregisterBinder,
    )
    val locationListeners: List<Pair<String, IInterface>> get() = listeners.listeners()

    operator fun invoke(classLoader: ClassLoader) {
        val cLocationManagerService =
            Reflection.findClassIfExists(
                "com.android.server.location.LocationManagerService",
                classLoader,
            )
        if (cLocationManagerService == null) {
            hookLocationManagerServiceV2(classLoader)
        } else {
            onService(cLocationManagerService)
        }
    }

    fun onService(cILocationManager: Class<*>) {

        if (FakeLoc.enableDebugLog) {
            Logger.debug("ILocationManager.Stub: class = $cILocationManager")
        }

        cILocationManager.classLoader!!.let {
            BasicLocationHook(it)
            GnssHook(it)
            LocationProviderManagerHook(it)

            MiuiBlurLocationProviderHook(it)
            MiuiLocationManagerHook(it)
            MiuiTelephonyManagerHook(it)
        }

        LocationNMEAHook(cILocationManager)

        if (
            cILocationManager
                .hookAllMethods(
                    "getLastLocation",
                    XposedInterface.Hooker intercept8@{ chain ->
                        val args = chain.args.toTypedArray()
                        var result = chain.proceed()

                        if (!FakeLoc.enable) return@intercept8 result

                        val location = result as? Location ?: Location("gps")

                        result = injectLocation(location)

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("getLastLocation: injected! $result")
                        }

                        result
                    },
                )
                .isEmpty()
        ) {
            Logger.error("hook getLastLocation failed")
        }

        cILocationManager.hookAllMethods(
            "requestLocationUpdates",
            XposedInterface.Hooker intercept9@{ chain ->
                val args = chain.args.toTypedArray()

                val provider =
                    kotlin
                        .runCatching { Reflection.callMethod(args[0], "getProvider") as? String }
                        .getOrNull() ?: "gps"

                val listener =
                    args.filterIsInstance<IInterface>().firstOrNull()
                        ?: run {
                            Logger.error(
                                "requestLocationUpdates: listener is null: ${chain.executable}"
                            )
                            return@intercept9 chain.proceed(args)
                        }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("requestLocationUpdates: injected! $listener")
                }

                if (FakeLoc.enable) {
                    addLocationListenerInner(provider, listener)
                    return@intercept9 null
                }
                val result = chain.proceed(args)
                if (result != false) addLocationListenerInner(provider, listener)
                result
            },
        )
        cILocationManager.hookAllMethods(
            "removeUpdates",
            XposedInterface.Hooker intercept10@{ chain ->
                val args = chain.args.toTypedArray()
                var result = chain.proceed()

                val listener =
                    args.filterIsInstance<IInterface>().firstOrNull()
                        ?: run {
                            Logger.error("removeUpdates: listener is null: ${chain.executable}")
                            return@intercept10 result
                        }
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("removeUpdates: injected! $listener")
                }

                if (result != false) removeLocationListenerInner(listener)

                result
            },
        )
        cILocationManager.hookAllMethods(
            "registerLocationListener",
            XposedInterface.Hooker intercept11@{ chain ->
                val args = chain.args.toTypedArray()

                val provider = args[0] as? String ?: "gps"
                val listener =
                    args.filterIsInstance<IInterface>().firstOrNull()
                        ?: run {
                            Logger.error(
                                "registerLocationListener: listener is null: ${chain.executable}"
                            )
                            return@intercept11 chain.proceed(args)
                        }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug(
                        "registerLocationListener: injected! $listener, from ${BinderUtils.getUidPackageNames()}"
                    )
                }

                if (FakeLoc.enable && (FakeLoc.disableRegisterLocationListener ||
                        (FakeLoc.disableFusedLocation && provider == "fused"))) {
                    addLocationListenerInner(provider, listener)
                    return@intercept11 null
                }
                val result = chain.proceed(args)
                if (result != false) addLocationListenerInner(provider, listener)
                result
            },
        )
        cILocationManager.hookAllMethods(
            "unregisterLocationListener",
            XposedInterface.Hooker intercept12@{ chain ->
                val args = chain.args.toTypedArray()
                var result = chain.proceed()

                val listener =
                    args.filterIsInstance<IInterface>().firstOrNull()
                        ?: run {
                            Logger.error(
                                "unregisterLocationListener: listener is null: ${chain.executable}"
                            )
                            return@intercept12 result
                        }
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("unregisterLocationListener: injected! $listener")
                }

                if (result != false) removeLocationListenerInner(listener)

                result
            },
        )

        run {
            cILocationManager.hookAllMethods(
                "addGnssBatchingCallback",
                XposedInterface.Hooker intercept13@{ chain ->
                    val args = chain.args.toTypedArray()

                    if (args.isEmpty() || args[0] == null) return@intercept13 chain.proceed(args)
                    val callback = args[0] ?: return@intercept13 chain.proceed(args)
                    val classCallback = callback.javaClass

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("addGnssBatchingCallback: injected!")
                    }

                    classCallback.onceHookAllMethod(
                        "onLocationBatch",
                        XposedInterface.Hooker intercept14@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (args.isEmpty()) return@intercept14 chain.proceed(args)

                            if (!FakeLoc.enable) {
                                return@intercept14 chain.proceed(args)
                            }

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("onLocationBatch: injected!")
                            }

                            val location =
                                (args[0] ?: return@intercept14 chain.proceed(args)) as Location
                            args[0] = injectLocation(location)

                            chain.proceed(args)
                        },
                    )

                    chain.proceed(args)
                },
            )
        }

        cILocationManager.hookAllMethods(
            "requestGeofence",
            XposedInterface.Hooker intercept15@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable && FakeLoc.disableRequestGeofence) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("requestGeofence: injected!")
                    }
                    return@intercept15 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "getFromLocation",
            XposedInterface.Hooker intercept16@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable && FakeLoc.disableGetFromLocation) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("getFromLocation: injected!")
                    }
                    return@intercept16 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "getFromLocationName",
            XposedInterface.Hooker intercept17@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable && FakeLoc.disableGetFromLocation) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("getFromLocationName: injected!")
                    }
                    return@intercept17 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "addTestProvider",
            XposedInterface.Hooker intercept18@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("addTestProvider: injected!")
                    }
                    return@intercept18 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "removeTestProvider",
            XposedInterface.Hooker intercept19@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("removeTestProvider: injected!")
                    }
                    return@intercept19 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "setTestProviderLocation",
            XposedInterface.Hooker intercept20@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("setTestProviderLocation: injected!")
                    }
                    return@intercept20 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "setTestProviderEnabled",
            XposedInterface.Hooker intercept21@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("setTestProviderEnabled: injected!")
                    }
                    return@intercept21 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "registerGnssStatusCallback",
            XposedInterface.Hooker registration@{ chain ->
                val args = chain.args
                val result = chain.proceed()
                if (result == false) return@registration result
                val callback = args.firstOrNull() as? IInterface ?: return@registration result
                // Install while simulation is stopped too, so an existing listener follows live toggles.
                callback.javaClass.onceHookAllMethod(
                    "onSvStatusChanged",
                    XposedInterface.Hooker status@{ event ->
                        val method = event.executable as? Method
                        if (method == null || !ReceiverGnssStatusAdapter.supports(method))
                            return@status event.proceed()
                        val context = GnssReceiverRuntime.activeContext() ?: return@status event.proceed()
                        val route = GnssListenerRouting.routeFor(event.thisObject, context.state.routing)
                        if (route == null || GnssReceiverRuntime.frameForRoute(route, context) == null)
                            return@status event.proceed()
                        // The managed worker owns simulated delivery and invokes ORIGIN.
                        null
                    },
                )
                GnssListenerRouting.register(callback, args.getOrNull(1) as? String)
                result
            },
        )
        cILocationManager.hookAllMethods(
            "unregisterGnssStatusCallback",
            XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                if (result != false) (chain.args.firstOrNull() as? IInterface)?.let(GnssListenerRouting::unregister)
                result
            },
        )
        cILocationManager.hookAllMethods(
            "startGnssBatch",
            XposedInterface.Hooker intercept24@{ chain ->
                val args = chain.args.toTypedArray()
                val result = chain.proceed(args)
                if (result == false) return@intercept24 result

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("startGnssBatch: injected!")
                }

                if (FakeLoc.enable && args.size >= 2) {
                    val listener =
                        args.filterIsInstance<IInterface>().firstOrNull()
                            ?: run {
                                Logger.error(
                                    "startGnssBatch: listener is null: ${chain.executable}"
                                )
                                return@intercept24 result
                            }

                    addLocationListenerInner("GnssBatch", listener)
                }

                result
            },
        )
        cILocationManager.hookAllMethods(
            "stopGnssBatch",
            XposedInterface.Hooker intercept25@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable && args.size >= 2) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("stopGnssBatch: injected!")
                    }
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "requestListenerFlush",
            XposedInterface.Hooker intercept26@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable && args.size >= 2) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("requestListenerFlush: injected!")
                    }

                    val listener =
                        args.filterIsInstance<IInterface>().firstOrNull()
                            ?: run {
                                Logger.error(
                                    "requestListenerFlush: listener is null: ${chain.executable}"
                                )
                                return@intercept26 chain.proceed(args)
                            }

                    addLocationListenerInner("gps", listener)
                    if (FakeLoc.disableRegisterLocationListener || FakeLoc.enable)
                        return@intercept26 null
                }

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "getCurrentLocation",
            XposedInterface.Hooker intercept27@{ chain ->
                val args = chain.args.toTypedArray()

                if (!FakeLoc.enable) return@intercept27 chain.proceed(args)
                val callback = args.getOrNull(2) ?: return@intercept27 chain.proceed(args)

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getCurrentLocation: injected!")
                }

                if (FakeLoc.disableGetCurrentLocation) {
                    return@intercept27 null
                }

                val classCallback = callback.javaClass
                classCallback.onceHookAllMethod(
                    "onLocation",
                    XposedInterface.Hooker intercept28@{ chain ->
                        val args = chain.args.toTypedArray()

                        val location =
                            args[0] as? Location ?: return@intercept28 chain.proceed(args)

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("onLocation(getCurrentLocation): injected!")
                        }

                        args[0] = injectLocation(location)

                        chain.proceed(args)
                    },
                )

                chain.proceed(args)
            },
        )

        cILocationManager.hookAllMethods(
            "sendExtraCommand",
            XposedInterface.Hooker intercept29@{ chain ->
                val args = chain.args.toTypedArray()

                if (args.size < 3) return@intercept29 chain.proceed(args)

                val provider = args[0] as String
                if (provider != "fused_ext" && !FakeLoc.enable)
                    return@intercept29 chain.proceed(args)
                val command = args[1] as String
                val outResult = args[2] as? Bundle

                if (FakeLoc.disableFusedLocation && provider == "fused") {
                    return@intercept29 false
                }

                if (provider == "gps" && FakeLoc.enable) {
                    return@intercept29 false
                }

                if (provider == "LOCATION_BIG_DATA") {
                    return@intercept29 false
                }

                if (provider != "fused_ext") {
                    if (FakeLoc.enableDebugLog)
                        Logger.debug(
                            "sendExtraCommand provider: $provider, command: $command, result: pending"
                        )
                    return@intercept29 chain.proceed(args)
                }
                if (outResult == null) return@intercept29 chain.proceed(args)

                if (handleInstruction(command, outResult)) {
                    return@intercept29 true
                }

                chain.proceed(args)
            },
        )

        if (
            com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
                    cILocationManager,
                    "isProviderEnabledForUser",
                    XposedInterface.Hooker intercept30@{ chain ->
                        val args = chain.args.toTypedArray()

                        if (args.size < 2 || args[0] == null) return@intercept30 chain.proceed(args)
                        val provider = args[0] as String
                        var userId = args[1] as Int
                        if (provider == "fused_ext") {
                            if (userId == 0) {
                                userId = BinderUtils.getCallerUid()
                            }
                            return@intercept30 BinderUtils.isLocationProviderEnabled(userId)
                        } else if (FakeLoc.enable && provider == "network") {
                            return@intercept30 false
                        } else if (
                            FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused"
                        ) {
                            return@intercept30 false
                        } else {
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug(
                                    "isProviderEnabledForUser provider: $provider, userId: $userId"
                                )
                            }
                        }

                        chain.proceed(args)
                    },
                )
                .isEmpty()
        ) {
            com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
                cILocationManager,
                "isProviderEnabled",
                XposedInterface.Hooker intercept31@{ chain ->
                    val args = chain.args.toTypedArray()

                    if (args.isEmpty() || args[0] == null) return@intercept31 chain.proceed(args)
                    val provider = args[0] as String
                    val userId = BinderUtils.getCallerUid()
                    if (provider == "fused_ext" && BinderUtils.isLocationProviderEnabled(userId)) {
                        return@intercept31 true
                    } else if (FakeLoc.enable && provider == "network") {
                        return@intercept31 false
                    } else if (
                        FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused"
                    ) {
                        return@intercept31 false
                    }

                    chain.proceed(args)
                },
            )
        }

        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cILocationManager,
            "setExtraLocationControllerPackageEnabled",
            XposedInterface.Hooker intercept32@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable) {
                    args[0] = false
                }

                chain.proceed(args)
            },
        )

        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cILocationManager,
            "setExtraLocationControllerPackage",
            XposedInterface.Hooker intercept33@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable) {
                    return@intercept33 null
                }

                chain.proceed(args)
            },
        )
    }

    private fun hookILocationListener(listener: Any) {
        val classListener = listener.javaClass
        if (FakeLoc.enableDebugLog)
            Logger.debug("will hook ILocationListener: ${classListener.name}")

        if (classListener.declaredMethods.none { it.name == "onLocationChanged" }) {
            Logger.error("No supported location callback on ${classListener.name}")
            return
        }
        classListener.onceHookAllMethod(
                    "onLocationChanged",
                    XposedInterface.Hooker intercept34@{ chain ->
                        val args = chain.args.toTypedArray()

                        if (args.isEmpty()) return@intercept34 chain.proceed(args)
                        if (!FakeLoc.enable) return@intercept34 chain.proceed(args)
                        val context = ProviderView.capture()

                        when (args[0]) {
                            is Location -> {
                                val location =
                                    args[0] as? Location
                                        ?: run {
                                            return@intercept34 null
                                        }
                                args[0] = injectLocation(location, context = context)
                            }

                            is List<*> -> {
                                val locations = args[0] as List<*>
                                if (locations.any { it !is Location }) return@intercept34 chain.proceed(args)
                                args[0] = locations.map { injectLocation(it as Location, context = context) }
                            }
                            else -> Logger.error("onLocationChanged args is not `Location`")
                        }

                        if (context.state.configuration.debugLog) {
                            Logger.debug("${chain.executable}: injected! ${args[0]}")
                        }

                        chain.proceed(args)
                    },
                )
    }

    private fun addLocationListenerInner(provider: String, listener: IInterface) {
        listeners.register(provider, listener)
    }

    private fun removeLocationListenerInner(listener: IInterface) {
        listeners.unregister(listener)
    }

    @Synchronized
    fun callOnLocationChanged() = LocationListenerDelivery.deliver(locationListeners)

    private fun hookLocationManagerServiceV2(classLoader: ClassLoader) {
        kotlin
            .runCatching {
                Reflection.findClass("android.location.ILocationManager\$Stub", classLoader)
            }
            .onSuccess {
                fun hookOnTransactForServiceInstance(m: Method) {
                    val isHooked = AtomicBoolean(false)
                    com.system.location.service.hook.scope.HookInstaller.hookMethod(
                        m,
                        XposedInterface.Hooker intercept35@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (chain.thisObject == null || args.size < 4)
                                return@intercept35 chain.proceed(args)

                            val service = chain.thisObject ?: return@intercept35 chain.proceed(args)
                            val code = args[0] as? Int ?: return@intercept35 chain.proceed(args)
                            val data = args[1] as? Parcel ?: return@intercept35 chain.proceed(args)
                            val reply = args[2] as? Parcel ?: return@intercept35 chain.proceed(args)
                            val flags = args[3] as? Int ?: return@intercept35 chain.proceed(args)

                            if (isHooked.compareAndSet(false, true)) {
                                onService(service.javaClass)
                            }

                            if (!FakeLoc.enable) {
                                return@intercept35 chain.proceed(args)
                            }

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("ILocationManager.Stub: onTransact(code=$code)")
                            }
                            if (FakeLoc.enable && code == 43) return@intercept35 true

                            chain.proceed(args)
                        },
                    )
                }

                it.declaredMethods.forEach {
                    if (it.name == "onTransact") {
                        hookOnTransactForServiceInstance(it)

                        return@forEach
                    }
                }
            }
            .onFailure { Logger.error("ILocationManager.Stub not found", it) }
    }

    private fun handleInstruction(command: String, rely: Bundle): Boolean {
        return RemoteCommandHandler.handleInstruction(command, rely)
    }
}
