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
import com.system.location.service.hook.utils.toLocation
import io.github.libxposed.api.XposedInterface
import java.util.Collections

/** Vendor and platform provider reporting adapters. */
internal object GnssProviderHooks {
    fun install(classLoader: ClassLoader) {
        kotlin
            .runCatching {
                val cGnssLocationProvider =
                    Reflection.findClassIfExists(
                        "com.android.location.provider.LocationProviderBase",
                        classLoader,
                    ) ?: return@runCatching
                if (BlindHookLocation(cGnssLocationProvider, classLoader) == 0) {
                    cGnssLocationProvider.onceHookMethod(
                        "reportLocation",
                        Location::class.java,
                        callback =
                            XposedInterface.Hooker intercept49@{ chain ->
                                val args = chain.args.toTypedArray()

                                if (!FakeLoc.enable) return@intercept49 chain.proceed(args)
                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug("LocationProviderBase.reportLocation: injected!")
                                }
                                args[0] = injectLocation(args[0] as Location)

                                chain.proceed(args)
                            },
                    )
                }
                cGnssLocationProvider.onceHookMethod(
                    "reportLocations",
                    List::class.java,
                    callback =
                        XposedInterface.Hooker intercept50@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (!FakeLoc.enable) return@intercept50 chain.proceed(args)
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("LocationProviderBase.reportLocations: injected!")
                            }
                            args[0] = (args[0] as List<*>).map { injectLocation(it as Location) }

                            chain.proceed(args)
                        },
                )
            }
            .onFailure { Logger.warn("Failed to hook LocationProviderBase", it) }

        kotlin
            .runCatching {
                val cGnssLocationProvider =
                    Reflection.findClass(
                        "com.android.server.location.gnss.GnssLocationProvider",
                        classLoader,
                    )
                cGnssLocationProvider.onceHookMethod(
                    "onReportLocation",
                    Boolean::class.java,
                    Location::class.java,
                    callback =
                        XposedInterface.Hooker intercept51@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (!FakeLoc.enable) return@intercept51 chain.proceed(args)

                            args[1] = injectLocation(args[1] as Location)

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug(
                                    "GnssLocationProvider.onReportLocation: injected! ${args[1]}"
                                )
                            }

                            chain.proceed(args)
                        },
                )

                cGnssLocationProvider.onceHookMethod(
                    "onReportLocations",
                    Boolean::class.java,
                    Array<Location>::class.java,
                    callback =
                        XposedInterface.Hooker intercept52@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (
                                !LocationHookArguments.rewriteBatch<Location>(
                                    FakeLoc.enable,
                                    args,
                                    1,
                                ) {
                                    injectLocation(it)
                                }
                            )
                                return@intercept52 chain.proceed(args)

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug(
                                    "GnssLocationProvider.onReportLocations: injected! ${args[1]}"
                                )
                            }

                            chain.proceed(args)
                        },
                )

                cGnssLocationProvider.onceHookMethod(
                    "getCellType",
                    CellInfo::class.java,
                    callback =
                        XposedInterface.Hooker intercept53@{ chain ->
                            val args = chain.args.toTypedArray()

                            if (!FakeLoc.enable) return@intercept53 chain.proceed(args)
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("GnssLocationProvider.getCellType: injected!")
                            }

                            return@intercept53 0
                        },
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cGnssLocationProvider.onceHookMethod(
                        "getCidFromCellIdentity",
                        CellIdentity::class.java,
                        callback =
                            XposedInterface.Hooker intercept54@{ chain ->
                                val args = chain.args.toTypedArray()

                                if (!FakeLoc.enable) return@intercept54 chain.proceed(args)
                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug(
                                        "GnssLocationProvider.getCidFromCellIdentity: injected!"
                                    )
                                }

                                return@intercept54 -1L
                            },
                    )

                    cGnssLocationProvider.onceHookMethod(
                        "setRefLocation",
                        Int::class.java,
                        CellIdentity::class.java,
                        callback =
                            XposedInterface.Hooker intercept55@{ chain ->
                                val args = chain.args.toTypedArray()

                                if (!FakeLoc.enable) return@intercept55 chain.proceed(args)
                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug("GnssLocationProvider.setRefLocation: injected!")
                                }

                                args[0] = 114514 // disable AGPS

                                chain.proceed(args)
                            },
                    )
                }
            }
            .onFailure { Logger.warn("Failed to hook GnssLocationProvider", it) }
    }
}
