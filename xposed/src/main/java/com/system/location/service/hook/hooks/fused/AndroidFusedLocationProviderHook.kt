package com.system.location.service.hook.hooks.fused

import android.location.Location
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookMethod
import com.system.location.service.hook.utils.toClass
import io.github.libxposed.api.XposedInterface

object AndroidFusedLocationProviderHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        val cFusedLocationProvider =
            "com.android.location.fused.FusedLocationProvider".toClass(classLoader)
        if (cFusedLocationProvider == null) {
            Logger.warn("Failed to find FusedLocationProvider")
            return
        }

        if (!initDivineService("AndroidFusedLocationProvider")) {
            Logger.error("Failed to init DivineService in AndroidFusedLocationProvider")
            return
        }

        cFusedLocationProvider.hookMethod(
            "chooseBestLocation",
            Location::class.java,
            Location::class.java,
            callback =
                XposedInterface.Hooker intercept59@{ chain ->
                    val args = chain.args.toTypedArray()
                    var result = chain.proceed()

                    if (result == null) return@intercept59 result

                    if (FakeLoc.enable) {
                        result = injectLocation(result as Location)
                    }

                    result
                },
        )

        val cChildLocationListener =
            "com.android.location.fused.FusedLocationProvider\$ChildLocationListener"
                .toClass(classLoader)
        if (cChildLocationListener == null) {
            Logger.warn("Failed to find ChildLocationListener")
            return
        }

        BlindHookLocation(cChildLocationListener, classLoader)
    }
}
