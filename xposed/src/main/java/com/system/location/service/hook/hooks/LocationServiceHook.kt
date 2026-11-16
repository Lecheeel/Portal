@file:Suppress("KotlinConstantConditions")
@file:OptIn(ExperimentalUuidApi::class)

package com.system.location.service.hook.hooks

import android.location.Location
import android.location.LocationManager
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
import com.system.location.service.hook.utils.LocationCallerGuard
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.hookMethod
import com.system.location.service.hook.utils.onceHookAllMethod
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.core.sensors.SensorTargets
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import com.system.location.service.hook.runtime.CallbackSubscription
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

                        // A hidden provider must not be a "getLastLocation returns a fix"
                        // oracle while getAllProviders/getProviderProperties say it does not
                        // exist. Resolve through the same ILocationManager surface the caller
                        // used, then fail closed for names the caller cannot see.
                        val provider = runCatching {
                            Reflection.callMethod(args[0], "getProvider") as? String
                        }.getOrNull() ?: LocationManager.GPS_PROVIDER
                        if (!providerExists(chain.thisObject, provider)) return@intercept8 null

                        val location = result as? Location ?: run {
                            // Do not invent a fix while the platform location switch is off.
                            if (!LocationCallerGuard.isLocationEnabled()) return@intercept8 null
                            Location(provider)
                        }

                        // Coarse-only callers must not receive a GPS-precision fix.
                        result = if (LocationCallerGuard.isCoarse()) {
                            runCatching { coarseLocation() }.getOrElse { injectLocation(location) }
                        } else {
                            injectLocation(location)
                        }

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

                // Run the framework path first: it performs the caller permission, AppOps and
                // provider-enabled checks. Only then does synthetic delivery own the listener.
                hookILocationListener(listener)
                val result = chain.proceed(args)
                if (result != false && FakeLoc.enable && providerExists(chain.thisObject, provider)) {
                    addLocationListenerInner(provider, listener)
                }
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

                val suppress = FakeLoc.enable && (FakeLoc.disableRegisterLocationListener ||
                    (FakeLoc.disableFusedLocation && provider == "fused"))
                // Unknown/hidden provider names must observe the framework's own error instead
                // of a synthetic registration that contradicts getProviderProperties.
                if (!providerExists(chain.thisObject, provider)) return@intercept11 chain.proceed(args)
                if (suppress) {
                    // The explicit suppression path still has to preserve the caller's permission
                    // decision; otherwise a denied app receives updates from the hook instead.
                    if (!LocationCallerGuard.canReadLocation()) return@intercept11 chain.proceed(args)
                    hookILocationListener(listener)
                    addLocationListenerInner(provider, listener)
                    return@intercept11 null
                }
                hookILocationListener(listener)
                val result = chain.proceed(args)
                if (result != false && FakeLoc.enable) addLocationListenerInner(provider, listener)
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
                val callback = args.firstOrNull() as? IInterface
                // Install the callback guard and routing before the framework can synchronously
                // emit onStarted/onFirstFix during registration.
                if (callback != null) {
                    installGnssStatusCallback(callback)
                    GnssListenerRouting.register(callback, args.getOrNull(1) as? String)
                }
                val result = try {
                    chain.proceed()
                } catch (failure: Throwable) {
                    callback?.let(GnssListenerRouting::unregister)
                    throw failure
                }
                if (result == false) callback?.let(GnssListenerRouting::unregister)
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

                    if (!LocationCallerGuard.canReadLocation()) return@intercept26 chain.proceed(args)
                    hookILocationListener(listener)
                    addLocationListenerInner("gps", listener)
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
                    // A denied caller must still observe the framework's SecurityException.
                    if (!LocationCallerGuard.canReadLocation()) return@intercept27 chain.proceed(args)
                    return@intercept27 null
                }

                // Capture the caller's access level before the callback runs on another thread.
                val coarse = LocationCallerGuard.isCoarse()
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

                        args[0] = if (coarse) {
                            runCatching { coarseLocation() }.getOrElse { injectLocation(location) }
                        } else {
                            injectLocation(location)
                        }

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

                if (provider != "fused_ext") {
                    if (FakeLoc.enableDebugLog)
                        Logger.debug(
                            "sendExtraCommand provider: $provider, command: $command, result: pending"
                        )
                    return@intercept29 chain.proceed(args)
                }
                if (outResult == null) return@intercept29 chain.proceed(args)

                if (command == com.system.location.service.hook.hooks.telephony.CarrierIdentitySnapshotCommand.COMMAND) {
                    return@intercept29 com.system.location.service.hook.hooks.telephony
                        .CarrierIdentitySnapshotCommand.read(outResult)
                }

                if (command == "get_anti_detect_state") {
                    // Read-only experiment flags for an explicitly selected sensor target.
                    val uid = BinderUtils.getCallerUid()
                    val packages = runCatching {
                        BinderUtils.getUidPackageNames(uid = uid)?.toSet().orEmpty()
                    }.getOrDefault(emptySet())
                    val targets = FakeLoc.runtimeSnapshots.current().configuration.sensorTargets
                    if (!SensorTargets.allows(uid, packages, targets)) return@intercept29 false
                    outResult.putBoolean("anti_detect", FakeLoc.antiDetectEnabled)
                    outResult.putBoolean("stack_trace", FakeLoc.stackTraceCleanEnabled)
                    outResult.putBoolean("stack_trace_replace", FakeLoc.stackTraceReplaceEnabled)
                    return@intercept29 true
                }

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
                        if (provider == "fused_ext") {
                            // Anti-fingerprint consistency (work package 9): ProviderRegistryHook
                            // already answers "provider does not exist" for ordinary callers, so
                            // reporting fused_ext as *enabled* to them would contradict that and
                            // expose the hiding layer itself. Only channel participants (the
                            // controller and framework peers) may see the enabled answer; every
                            // other caller proceeds to the natural result, which is false because
                            // fused_ext is not a registered provider.
                            val callerUid = BinderUtils.getCallerUid()
                            if (BinderUtils.callerRole(callerUid) ==
                                com.system.location.service.hook.security.CallerRole.DENIED
                            ) {
                                return@intercept30 chain.proceed(args)
                            }
                            // Channel callers resolve by UID; the legacy code conflated the
                            // queried userId with the caller UID, which broke callers running in
                            // managed profiles (userId != 0).
                            return@intercept30 BinderUtils.isLocationProviderEnabled(callerUid)
                        } else if (
                            FakeLoc.enable && FakeLoc.disableFusedLocation && provider == "fused"
                        ) {
                            return@intercept30 false
                        } else {
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug(
                                    "isProviderEnabledForUser provider: $provider, userId: ${args[1]}"
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
                    if (provider == "fused_ext") {
                        // Same consistency rule as isProviderEnabledForUser: ordinary callers must
                        // observe the natural result (false), channel participants the enabled one.
                        val callerUid = BinderUtils.getCallerUid()
                        if (BinderUtils.callerRole(callerUid) ==
                            com.system.location.service.hook.security.CallerRole.DENIED
                        ) {
                            return@intercept31 chain.proceed(args)
                        }
                        if (BinderUtils.isLocationProviderEnabled(callerUid)) {
                            return@intercept31 true
                        }
                        return@intercept31 chain.proceed(args)
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

    internal fun installGnssStatusCallback(callback: IInterface) {
        val statusHook = XposedInterface.Hooker status@{ event ->
            val method = event.executable as? Method ?: return@status event.proceed()
            if (method.name == "onSvStatusChanged" &&
                !ReceiverGnssStatusAdapter.supports(method)) return@status event.proceed()
            // Protection is decided by the routing policy, not by frame readiness. A missing
            // synthetic frame must suppress the real status/TTFF callback, never pass it through.
            val context = GnssReceiverRuntime.protectionContext() ?: return@status event.proceed()
            val route = GnssListenerRouting.routeFor(event.thisObject, context.state.routing)
                ?: return@status event.proceed()
            null
        }
        listOf("onSvStatusChanged", "onGnssStarted", "onFirstFix", "onGnssStopped").forEach { name ->
            callback.javaClass.onceHookAllMethod(name, statusHook)
        }
    }

    internal fun restoreLocationSubscriptions(subscriptions: List<CallbackSubscription>) {
        subscriptions.forEach { subscription ->
            if (listeners.restore(subscription)) {
                hookILocationListener(subscription.listener)
                ListenerRouting.register(subscription.listener, subscription.uid)
            }
        }
    }

    fun locationSubscriptions(): List<CallbackSubscription> = listeners.subscriptions()

    /** Release this generation's death links after its subscriptions have been saved. */
    internal fun retireLocationSubscriptions() = listeners.clear()

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
                        // Synthetic delivery invokes the original method directly, so a callback
                        // that reaches this hook is a real provider update. Drop it for every
                        // listener we own; otherwise the app sees two callbacks per epoch.
                        if (listeners.contains(chain.thisObject)) return@intercept34 null
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
                                args[0] = injectLocationBatch(locations.map { it as Location }, context)
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

    /**
     * Resolve provider existence through the same service surface that answers
     * getAllProviders/getProviderProperties. A hidden provider therefore returns null from
     * getLastLocation instead of becoming a "provider exists" oracle.
     */
    private fun providerExists(service: Any?, provider: String): Boolean {
        val known = setOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            "fused",
        )
        return runCatching {
            Reflection.callMethod(service, "hasProvider", provider) as? Boolean
        }.getOrNull() ?: (provider in known)
    }

    internal fun isCoarse(listener: IInterface): Boolean = listeners.isCoarse(listener)

    /** Network-provider view with the platform's coarse obfuscation magnitude. */
    private fun coarseLocation(): Location =
        locationForProvider(LocationManager.NETWORK_PROVIDER).also {
            it.accuracy = maxOf(it.accuracy, COARSE_ACCURACY_METERS)
            it.verticalAccuracyMeters = maxOf(it.verticalAccuracyMeters, it.accuracy * 2f)
        }

    private fun removeLocationListenerInner(listener: IInterface) {
        listeners.unregister(listener)
    }

    @Synchronized
    fun callOnLocationChanged() {
        try { LocationListenerDelivery.deliver(locationListeners) }
        finally {
            // A free-running 1 Hz poll can repeatedly observe the same leased sample around a
            // second boundary and then skip an epoch. Drive GNSS from accepted publications.
            com.system.location.service.hook.hooks.gnss.GnssSignalInjection.onSamplePublished()
        }
    }

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
                            // Work package 9 audit: the previous unconditional fake-success for
                            // transaction code 43 is removed. Cross-checking AOSP
                            // ILocationManager.aidl (android12/13/14/15/16-release) shows
                            // FIRST_CALL_TRANSACTION+42 — code 43 — is
                            // setExtraLocationControllerPackage(String) on every release Portal
                            // supports. Faking success for it broke both permission semantics
                            // (callers without LOCATION_HARDWARE got "true" instead of a
                            // SecurityException) and set/get consistency (callers with the
                            // permission got a silent no-op) — exactly the kind of fixed
                            // abnormal answer work package 9 removes. The natural result is
                            // restored; the onService capture above keeps working.
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

    private const val COARSE_ACCURACY_METERS = 2_000f
}
