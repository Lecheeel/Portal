package com.system.location.service.hook.hooks.fused

import android.location.Location
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.onceHook
import com.system.location.service.hook.utils.toClass
import io.github.libxposed.api.XposedInterface

object AndroidFusedLocationProviderHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader): Boolean {
        val cFusedLocationProvider =
            "com.android.location.fused.FusedLocationProvider".toClass(classLoader)
        if (cFusedLocationProvider == null) {
            Logger.warn("Failed to find FusedLocationProvider")
            return false
        }

        if (!initDivineService("AndroidFusedLocationProvider")) {
            Logger.error("Failed to init DivineService in AndroidFusedLocationProvider")
            return false
        }

        val chooseBestLocation = HookInstaller.findMethod(
            cFusedLocationProvider,
            "chooseBestLocation",
            Location::class.java,
            Location::class.java,
        )
        chooseBestLocation?.onceHook(
            XposedInterface.Hooker intercept59@{ chain ->
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
        val callbacks = if (cChildLocationListener == null) {
            Logger.warn("Failed to find ChildLocationListener")
            0
        } else {
            BlindHookLocation(cChildLocationListener, classLoader)
        }
        val installed = chooseBestLocation != null || callbacks > 0
        HookRuntime.log("fused hook class=${cFusedLocationProvider.name} chooseBest=${chooseBestLocation != null} callbacks=$callbacks installed=$installed")
        return installed
    }
}
