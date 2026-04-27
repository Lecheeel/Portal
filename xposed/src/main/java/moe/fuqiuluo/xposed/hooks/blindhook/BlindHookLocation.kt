package com.system.location.service.hook.hooks.blindhook

import android.location.Location
import de.robv.android.xposed.XposedBridge
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger

object BlindHookLocation: BaseLocationHook() {
    operator fun invoke(clazz: Class<*>, classLoader: ClassLoader): Int {
        return BlindHook(clazz, classLoader) { method, location: Location? ->
            if (location == null || !FakeLoc.enable) return@BlindHook location

            val newLoc = injectLocation(location)

            if (FakeLoc.enableDebugLog) {
                Logger.debug("${method.name} injected: $newLoc")
            }

            newLoc
        }
    }
}
