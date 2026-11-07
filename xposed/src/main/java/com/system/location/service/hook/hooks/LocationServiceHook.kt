@file:Suppress("KotlinConstantConditions")
@file:OptIn(ExperimentalUuidApi::class)

package com.system.location.service.hook.hooks

import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.RemoteCommandHandler
import com.system.location.service.hook.hooks.gnss.GnssHook
import com.system.location.service.hook.hooks.gnss.GnssListenerRouting
import com.system.location.service.hook.hooks.gnss.GnssStatusBridge
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
import com.system.location.service.hook.utils.toLocation
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.ExperimentalUuidApi

import android.os.Build
import android.os.DeadObjectException
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.utils.afterHook
import com.system.location.service.hook.utils.beforeHook
import java.lang.reflect.InvocationTargetException

internal object LocationServiceHook : BaseLocationHook() {
    val locationListeners = LinkedBlockingQueue<Pair<String, IInterface>>()

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

                addLocationListenerInner(provider, listener)

                if (FakeLoc.enable) {
                    return@intercept9 null
                }

                if (FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused") {
                    return@intercept9 null
                }

                chain.proceed(args)
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

                removeLocationListenerInner(listener)

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

                addLocationListenerInner(provider, listener)

                if (FakeLoc.enable && FakeLoc.disableRegisterLocationListener) {
                    return@intercept11 null
                }

                if (FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused") {
                    return@intercept11 null
                }

                chain.proceed(args)
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

                removeLocationListenerInner(listener)

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

                if (FakeLoc.disableRequestGeofence && !FakeLoc.enableAGPS) {
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

                if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
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

                if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
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

                if (FakeLoc.enable && !FakeLoc.enableAGPS) {
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

                if (FakeLoc.enable && !FakeLoc.enableAGPS) {
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

                if (FakeLoc.enable && !FakeLoc.enableAGPS) {
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

                if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("setTestProviderEnabled: injected!")
                    }
                    return@intercept21 null
                }

                chain.proceed(args)
            },
        )

        if (
            com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
                    cILocationManager,
                    "registerGnssStatusCallback",
                    XposedInterface.Hooker intercept22@{ chain ->
                        val args = chain.args.toTypedArray()
                        var result = chain.proceed()

                        if (args.isEmpty() || args[0] == null) return@intercept22 result

                        val callback = args[0] ?: return@intercept22 result
                        val cIGnssStatusListener = callback.javaClass
                        if (callback is IInterface) {
                            GnssListenerRouting.register(callback, args.getOrNull(1) as? String)
                        }

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("registerGnssStatusCallback: injected!")
                        }
                        if (!FakeLoc.enable || !FakeLoc.enableMockGnss) {
                            return@intercept22 result
                        }

                        if (
                            cIGnssStatusListener
                                .onceHookAllMethod(
                                    "onSvStatusChanged",
                                    XposedInterface.Hooker intercept23@{ chain ->
                                        val args = chain.args.toTypedArray()

                                        if (!FakeLoc.enable || !FakeLoc.enableMockGnss)
                                            return@intercept23 chain.proceed(args)

                                        val mockGps =
                                            GnssStatusBridge.toMockData(
                                                GnssStatusBridge.currentFrame(
                                                    GnssListenerRouting.fixedPointFor(
                                                        chain.thisObject
                                                    )
                                                )
                                            )
                                        val svCount = mockGps.svCount

                                        if (args[0] is Int) {
                                            args[0] = svCount
                                            args[1] = mockGps.svidWithFlags
                                            args[2] = mockGps.cn0s
                                            args[3] = mockGps.elevations
                                            args[4] = mockGps.azimuths
                                            if (args.size > 5) {
                                                args[5] = mockGps.carrierFreqs
                                            }

                                            if (args.size > 6) {
                                                args[6] = GnssStatusBridge.basebandCn0(mockGps)
                                            }
                                            return@intercept23 chain.proceed(args)
                                        }

                                        if (
                                            args[0] != null &&
                                                args[0]!!.javaClass.name ==
                                                    "android.location.GnssStatus"
                                        ) {
                                            runCatching {
                                                    val mConstructor =
                                                        args[0]!!
                                                            .javaClass
                                                            .declaredConstructors
                                                            .firstOrNull {
                                                                it.parameterTypes.size == 7
                                                            }
                                                            .also { it?.isAccessible = true }

                                                    if (mConstructor != null) {
                                                        args[0] =
                                                            mConstructor.newInstance(
                                                                svCount,
                                                                mockGps.svidWithFlags,
                                                                mockGps.cn0s,
                                                                mockGps.elevations,
                                                                mockGps.azimuths,
                                                                mockGps.carrierFreqs,
                                                                GnssStatusBridge.basebandCn0(
                                                                    mockGps
                                                                ),
                                                            )
                                                    } else {
                                                        Logger.error(
                                                            "onSvStatusChanged: unsupported version: ${chain.executable}, constructor not found"
                                                        )
                                                    }
                                                }
                                                .onFailure { HookRuntime.log(it) }
                                            return@intercept23 chain.proceed(args)
                                        }

                                        Logger.error(
                                            "onSvStatusChanged: unsupported version: ${chain.executable}"
                                        )

                                        chain.proceed(args)
                                    },
                                )
                                .isEmpty()
                        ) {
                            Logger.error("find onSvStatusChanged failed!")
                        }

                        result
                    },
                )
                .isEmpty()
        ) {
            HookRuntime.log("[LocationService] hook registerGnssStatusCallback failed")
        }
        cILocationManager.hookAllMethods(
            "startGnssBatch",
            XposedInterface.Hooker intercept24@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("startGnssBatch: injected!")
                }

                if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                    val listener =
                        args.filterIsInstance<IInterface>().firstOrNull()
                            ?: run {
                                Logger.error(
                                    "startGnssBatch: listener is null: ${chain.executable}"
                                )
                                return@intercept24 chain.proceed(args)
                            }

                    addLocationListenerInner("GnssBatch", listener)
                    hookILocationListener(listener)
                }

                chain.proceed(args)
            },
        )
        cILocationManager.hookAllMethods(
            "stopGnssBatch",
            XposedInterface.Hooker intercept25@{ chain ->
                val args = chain.args.toTypedArray()

                if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
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

                if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
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

                    hookILocationListener(listener)
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

        if(com.system.location.service.hook.scope.HookInstaller.hookAllMethods(classListener, "onLocationChanged", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty()) return
                    if (!FakeLoc.enable) return

                    when (param.args[0]) {
                        is Location -> {
                            val location = param.args[0] as? Location ?: run {
                                param.result = null
                                return
                            }
                            param.args[0] = injectLocation(location)
                        }

                        is List<*> -> {
                            val locations = param.args[0] as List<*>
                            param.args[0] = locations.map { injectLocation(it as Location) }
                        }
                        else -> Logger.error("onLocationChanged args is not `Location`")
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method}: injected! ${param.args[0]}")
                    }
                }
            }).isEmpty()) {
            Logger.error("hook onLocationChanged failed")
            return // If the hook fails, the listener is not added
        }
    }

//    private fun startDaemon(classLoader: ClassLoader) {
//        //val cIRemoteCallback = XposedHelpers.findClass("android.os.IRemoteCallback", classLoader)
//        thread(
//            name = "LocationUpdater",
//            isDaemon = true,
//            start = true,
//        ) {
//            while (true) {
//                kotlin.runCatching {
//                    if (!FakeLoc.enable) {
//                        Thread.sleep(3000)
//                        return@runCatching
//                    } else {
//                        Thread.sleep(FakeLoc.updateInterval)
//                    }
//
//                    if (!FakeLoc.enable) return@runCatching // Prevent the last loop from being executed
//
//                    if (FakeLoc.enableDebugLog)
//                        Logger.debug("LocationUpdater: callOnLocationChanged: ${locationListeners.size}")
//
//                    callOnLocationChanged()
//                }.onFailure {
//                    Logger.error("LocationUpdater", it)
//                }
//            }
//        }
//    }

    private fun addLocationListenerInner(provider: String, listener: IInterface) {
        ListenerRouting.register(listener)
        val mDeathRecipient = object: IBinder.DeathRecipient {
            override fun binderDied() {}
            override fun binderDied(who: IBinder) {
                who.unlinkToDeath(this, 0)
                removeLocationListenerByBinder(who)
            }
        }
        listener.asBinder().linkToDeath(mDeathRecipient, 0)
        locationListeners.add(provider to listener)
        hookILocationListener(listener)
    }

    private fun removeLocationListenerInner(listener: IInterface) {
        removeLocationListenerByBinder(listener.asBinder())
    }

    private fun removeLocationListenerByBinder(binder: IBinder) {
        ListenerRouting.unregisterBinder(binder)
        locationListeners.removeIf { it.second.asBinder() == binder }
    }

    @Synchronized
    fun callOnLocationChanged() {
        if (!FakeLoc.enable) return
        val sample = FakeLoc.snapshot(force = true)
        if (FakeLoc.enableDebugLog) {
            Logger.debug("==> callOnLocationChanged: ${locationListeners.size}")
        }
        locationListeners.forEach { listenerWithProvider ->
            val listener = listenerWithProvider.second
            if (!FakeLoc.enable) return
            val route = ListenerRouting.route(listener)
            if (route is ListenerRouting.Route.Denied) return@forEach
            val scenarioId = (route as? ListenerRouting.Route.Scenario)?.scenarioId
            val point = (route as? ListenerRouting.Route.Point)?.point
            val location = sampleForProvider(if (listenerWithProvider.first == "GnssBatch") "gps" else listenerWithProvider.first, scenarioId, point)
                .toLocation(if (listenerWithProvider.first == "GnssBatch") "gps" else listenerWithProvider.first)
            var called = false
            var error: Throwable? = null
            kotlin.runCatching {
                val locations = listOf(location)
                val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", locations, null)
                XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(locations, null))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return@forEach
                }
                error = it
            }

            if (!called) runCatching {
                val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", location)
                XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(location))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return@forEach
                }
                error = it
            }

            if (!called) {
                Logger.error("Location listener delivery failed", error ?: IllegalStateException("No supported listener method"))
            }
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("==> callOnLocationChanged: end")
        }
    }

    private fun hookLocationManagerServiceV2(classLoader: ClassLoader) {
        // As a system_server, the hook can get all the location information here
        kotlin.runCatching {
            XposedHelpers.findClass("android.location.ILocationManager\$Stub", classLoader)
        }.onSuccess {
            fun hookOnTransactForServiceInstance(m: Method) {
                val isHooked = AtomicBoolean(false)
                com.system.location.service.hook.scope.HookInstaller.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param?.thisObject == null || param.args.size < 4) return

                        val thisObject = param.thisObject
                        val code = param.args[0] as? Int ?: return
                        val data = param.args[1] as? Parcel ?: return
                        val reply = param.args[2] as? Parcel ?: return
                        val flags = param.args[3] as? Int ?: return

                        if (isHooked.compareAndSet(false, true)) {
                            onService(thisObject.javaClass)
                        }

                        if (!FakeLoc.enable) {
                            return
                        }

                        if (FakeLoc.enable && code == 43) {
                            param.result = true
                        }

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("ILocationManager.Stub: onTransact(code=$code)")
                        }
                    }
                })
            }

            it.declaredMethods.forEach {
                if (it.name == "onTransact") {
                    hookOnTransactForServiceInstance(it)

                    // Hey, hey, you've found onTransact, what else are you looking for
                    // It's time to end the cycle! BaKa!
                    return@forEach
                }
            }
        }.onFailure {
            Logger.error("ILocationManager.Stub not found", it)
        }

//        // This is the intrusive hook
//        kotlin.runCatching {
//            XposedHelpers.findClass("android.location.ILocationManager\$Stub\$Proxy", cLocationManager.classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                XposedBridge.hookMethod(it, object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam?) {
//                        if (param == null) return
//
//                        XposedBridge.log("[LocationService] ILocationManager.Stub.Proxy: c = ${param.thisObject?.javaClass}, m = ${param.method}")
//                    }
//                })
//            }
//        }
    }

    private inline fun handleInstruction(command: String, rely: Bundle): Boolean {
        return RemoteCommandHandler.handleInstruction(command, rely)
    }
}

