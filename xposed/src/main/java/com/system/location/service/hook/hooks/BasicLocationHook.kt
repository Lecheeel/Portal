package com.system.location.service.hook.hooks

import android.location.Location
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.toClassOrThrow
import com.system.location.service.hook.utils.ProviderView
import io.github.libxposed.api.XposedInterface

object BasicLocationHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {

        kotlin
            .runCatching {
                val cLocationResult = "android.location.LocationResult".toClassOrThrow(classLoader)
                BlindHookLocation(cLocationResult, classLoader)

                cLocationResult.hookAllMethods(
                    "asList",
                    XposedInterface.Hooker intercept1@{ chain ->
                        val args = chain.args.toTypedArray()
                        var result = chain.proceed()

                        if (!FakeLoc.enable) return@intercept1 result

                        val locations = (result as? List<*>)?.takeIf { it.all { item -> item is Location } }
                            ?: return@intercept1 result
                        val context = ProviderView.capture()
                        result = injectLocationBatch(locations.map { it as Location }, context)

                        result
                    },
                )

                cLocationResult.hookAllMethods(
                    "writeToParcel",
                    XposedInterface.Hooker intercept2@{ chain ->
                        val args = chain.args.toTypedArray()

                        if (!FakeLoc.enable) return@intercept2 chain.proceed(args)

                        val locationResult = chain.thisObject ?: return@intercept2 chain.proceed(args)
                        val mLocationsField =
                            Reflection.findFieldIfExists(
                                locationResult.javaClass,
                                "mLocations",
                            )
                        if (mLocationsField == null) {
                            Logger.error("Failed to find mLocations in LocationResult")
                            return@intercept2 chain.proceed(args)
                        }
                        mLocationsField.isAccessible = true
                        LocationResultIsolation.writeWithLocations(locationResult, mLocationsField,
                            rewrite = { locations ->
                                if (locations.isEmpty() || locations.any { it !is Location }) null
                                else injectLocationBatch(locations.map { it as Location }, ProviderView.capture())
                            },
                            write = { chain.proceed(args) },
                        )
                    },
                )
            }
            .onFailure { Logger.error("Failed to hook LocationResult", it) }

        Location::class
            .java
            .hookAllMethods(
                "set",
                XposedInterface.Hooker intercept3@{ chain ->
                    val args = chain.args.toTypedArray()

                    if (!FakeLoc.enable) return@intercept3 chain.proceed(args)
                    args[0] = injectLocation(args[0] as Location)

                    chain.proceed(args)
                },
            )
    }
}
