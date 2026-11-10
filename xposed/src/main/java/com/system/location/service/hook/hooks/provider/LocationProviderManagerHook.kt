package com.system.location.service.hook.hooks.provider

import android.location.Location
import android.os.Build
import android.telephony.CellIdentity
import android.telephony.CellInfo
import com.system.location.service.hook.hooks.BasicLocationHook.injectLocation
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.LocationHookArguments
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.hook.utils.hook
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.onceHook
import com.system.location.service.hook.utils.onceHookAllMethod
import com.system.location.service.hook.utils.onceHookMethod
import io.github.libxposed.api.XposedInterface
import java.util.Collections

object LocationProviderManagerHook {
    private val hookOnFetchLocationResult =
        XposedInterface.Hooker intercept42@{ chain ->
            val args = chain.args.toTypedArray()

            if (args.isEmpty() || args[0] == null) return@intercept42 chain.proceed(args)
            if (!FakeLoc.enable) return@intercept42 chain.proceed(args)

            if (FakeLoc.enableDebugLog) {
                Logger.debug("${chain.executable}: injected!")
            }

            val locationResult = args[0] ?: return@intercept42 chain.proceed(args)
            val mLocationsField =
                Reflection.findFieldIfExists(locationResult.javaClass, "mLocations")
            if (mLocationsField == null) {
                Logger.error("Failed to find mLocations in LocationResult")
                return@intercept42 chain.proceed(args)
            }
            mLocationsField.isAccessible = true
            val locations =
                mLocationsField.get(locationResult) as? List<*>
                    ?: return@intercept42 chain.proceed(args)
            if (locations.isEmpty() || locations.any { it !is Location })
                return@intercept42 chain.proceed(args)
            val context = ProviderView.capture()
            mLocationsField.set(
                locationResult,
                locations.map { location ->
                    injectLocation(location as Location, context = context)
                },
            )

            chain.proceed(args)
        }

    operator fun invoke(classLoader: ClassLoader) {
        hookLocationProviderManager(classLoader)
        hookDelegateLocationProvider(classLoader)
        hookPassiveLocationProvider(classLoader)
        hookProxyLocationProvider(classLoader)
        hookAbstractLocationProvider(classLoader)
        GnssProviderHooks.install(classLoader)
        hookGeofenceProvider(classLoader)
    }

    private fun hookAbstractLocationProvider(classLoader: ClassLoader) {
        run {
            val cAbstractLocationProvider =
                Reflection.findClassIfExists(
                    "com.android.server.location.provider.AbstractLocationProvider",
                    classLoader,
                ) ?: return@run
            val cLocationResult =
                Reflection.findClassIfExists("android.location.LocationResult", classLoader)
                    ?: return@run
            val mReportLocation =
                Reflection.findMethodExactIfExists(
                    cAbstractLocationProvider,
                    "reportLocation",
                    cLocationResult,
                ) ?: return@run

            mReportLocation.onceHook(hookOnFetchLocationResult)
        }

        run {
            val cInternalState =
                Reflection.findClassIfExists(
                    "com.android.server.location.provider.AbstractLocationProvider\$InternalState",
                    classLoader,
                ) ?: return@run

            com.system.location.service.hook.scope.HookInstaller.hookAllConstructors(
                cInternalState,
                XposedInterface.Hooker intercept43@{ chain ->
                    val args = chain.args.toTypedArray()

                    val listener = args[0] ?: return@intercept43 chain.proceed(args)

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("AbstractLocationProvider.InternalState: injected!")
                    }

                    listener.javaClass.onceHookAllMethod(
                        "onReportLocation",
                        hookOnFetchLocationResult,
                    )

                    chain.proceed(args)
                },
            )
        }
    }

    private fun hookProxyLocationProvider(classLoader: ClassLoader) {
        val cProxyLocationProvider =
            Reflection.findClassIfExists(
                "com.android.server.location.provider.proxy.ProxyLocationProvider",
                classLoader,
            ) ?: return
    }

    private fun hookPassiveLocationProvider(classLoader: ClassLoader) {
        val cPassiveLocationProvider =
            Reflection.findClassIfExists(
                "com.android.server.location.provider.PassiveLocationProvider",
                classLoader,
            ) ?: return
        val cLocationResult =
            Reflection.findClassIfExists("android.location.LocationResult", classLoader) ?: return
        val updateLocation =
            Reflection.findMethodExactIfExists(
                cPassiveLocationProvider,
                "updateLocation",
                cLocationResult,
            ) ?: return

        updateLocation.hook(hookOnFetchLocationResult)
    }

    private fun hookDelegateLocationProvider(classLoader: ClassLoader) {
        val cDelegateLocationProvider =
            Reflection.findClassIfExists(
                "com.android.server.location.provider.DelegateLocationProvider",
                classLoader,
            ) ?: return

        val waitForInitialization =
            Reflection.findMethodExactIfExists(cDelegateLocationProvider, "waitForInitialization")
                ?: return
        val handle = java.util.concurrent.atomic.AtomicReference<XposedInterface.HookHandle?>()
        val matched = java.util.concurrent.atomic.AtomicBoolean(false)
        handle.set(
            waitForInitialization.onceHook(
                XposedInterface.Hooker { chain ->
                    if (FakeLoc.enableDebugLog)
                        Logger.debug("DelegateLocationProvider.waitForInitialization: injected!")
                    val locationResult =
                        Reflection.findClassIfExists("android.location.LocationResult", classLoader)
                    val provider = chain.thisObject
                    if (locationResult != null && provider != null) {
                        Reflection.findMethodExactIfExists(
                                provider.javaClass,
                                "onReportLocation",
                                locationResult,
                            )
                            ?.onceHook(hookOnFetchLocationResult)
                        Reflection.findMethodExactIfExists(
                                provider.javaClass,
                                "reportLocation",
                                locationResult,
                            )
                            ?.onceHook(hookOnFetchLocationResult)
                    }
                    matched.set(true)
                    handle.getAndSet(null)?.unhook()
                    chain.proceed()
                }
            )
        )
        // Invocation can race the return of the native registration handle.
        if (matched.get()) handle.getAndSet(null)?.unhook()
    }

    private fun hookLocationProviderManager(classLoader: ClassLoader) {
        val cLocationProviderManager =
            Reflection.findClassIfExists(
                "com.android.server.location.provider.LocationProviderManager",
                classLoader,
            ) ?: return
        BlindHookLocation(cLocationProviderManager, classLoader)

        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cLocationProviderManager,
            "setRealProvider",
            XposedInterface.Hooker intercept44@{ chain ->
                val args = chain.args.toTypedArray()

                val locationProvider = args[0]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setRealProvider: $locationProvider")
                }

                chain.proceed(args)
            },
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cLocationProviderManager,
            "setMockProvider",
            XposedInterface.Hooker intercept45@{ chain ->
                val args = chain.args.toTypedArray()

                val locationProvider = args[0]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setMockProvider: $locationProvider")
                }

                chain.proceed(args)
            },
        )
        com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
            cLocationProviderManager,
            "sendExtraCommand",
            XposedInterface.Hooker intercept46@{ chain ->
                val args = chain.args.toTypedArray()

                if (!FakeLoc.enable) return@intercept46 chain.proceed(args)
                if (args.size < 4) return@intercept46 chain.proceed(args)
                val command = args[2]

                if (command == "force_xtra_injection" || command == "CMD_SHOW_GPS_TIPS_CONFIG") {
                    return@intercept46 null
                }

                val extras = args[3]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("sendExtraCommand: $command, $extras")
                }

                chain.proceed(args)
            },
        )

        run {
            val hookedListeners = Collections.synchronizedSet(HashSet<String>())
            if (
                cLocationProviderManager
                    .onceHookAllMethod(
                        "getCurrentLocation",
                        XposedInterface.Hooker intercept47@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (!LocationHookArguments.canIntercept(FakeLoc.enable, args, 3))
                                return@intercept47 chain.proceed(args)

                            val callback = args[3]
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("getCurrentLocation injected: $callback")
                            }

                            if (FakeLoc.disableGetCurrentLocation) {
                                return@intercept47 null
                            }

                            val classCallback =
                                callback?.javaClass ?: return@intercept47 chain.proceed(args)
                            if (hookedListeners.contains(classCallback.name))
                                return@intercept47 chain.proceed(args) // Prevent repeated hooking
                            if (
                                com.system.location.service.hook.scope.HookInstaller.hookAllMethods(
                                        classCallback,
                                        "onLocation",
                                        XposedInterface.Hooker intercept48@{ chain ->
                                            val args = chain.args.toTypedArray()

                                            if (args.isEmpty())
                                                return@intercept48 chain.proceed(args)
                                            val location =
                                                (args[0] ?: return@intercept48 chain.proceed(args))
                                                    as Location

                                            if (FakeLoc.enableDebugLog)
                                                Logger.debug(
                                                    "onLocation(LocationProviderManager.getCurrentLocation): injected!"
                                                )
                                            args[0] = injectLocation(location)

                                            chain.proceed(args)
                                        },
                                    )
                                    .isEmpty()
                            ) {
                                Logger.error(
                                    "hook onLocation(LocationProviderManager.getCurrentLocation) failed"
                                )
                            }

                            hookedListeners.add(classCallback.name)

                            chain.proceed(args)
                        },
                    )
                    .isEmpty()
            ) {
                Logger.error("hook LocationProviderManager.getCurrentLocation failed")
            }
        }

        cLocationProviderManager.onceHookAllMethod("onReportLocation", hookOnFetchLocationResult)
    }

    private fun hookGeofenceProvider(classLoader: ClassLoader) {
        val cGeofenceManager =
            Reflection.findClassIfExists("com.android.server.geofence.GeofenceManager", classLoader)
                ?: return
        BlindHookLocation(cGeofenceManager, classLoader)
    }


}
