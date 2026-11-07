package com.system.location.service.hook.hooks.blindhook

import android.location.Location
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.onceHook
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member

object BlindHook {
    operator fun <T> invoke(
        clazz: Class<*>,
        classLoader: ClassLoader,
        handler: (Member, T?) -> T?,
    ): Int {
        var count = 0
        clazz.declaredMethods.forEach { method ->
            if (method.returnType == Location::class.java) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug(
                        "BlindHookV2 ${method.name}: ${method.parameterTypes.joinToString()}"
                    )
                }
                method.onceHook(
                    XposedInterface.Hooker { chain ->
                        @Suppress("UNCHECKED_CAST") val location = chain.proceed() as? T
                        if (location == null) null else handler(chain.executable, location)
                    }
                )
                count++
                return@forEach
            }
            val index = method.parameterTypes.indexOfFirst { it == Location::class.java }
            if (index >= 0) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug(
                        "BlindHookV2 ${method.name}: ${method.parameterTypes.joinToString()}"
                    )
                }
                method.onceHook(
                    XposedInterface.Hooker { chain ->
                        val args = chain.args.toTypedArray()
                        @Suppress("UNCHECKED_CAST") val location = args[index] as? T
                        if (location != null) args[index] = handler(chain.executable, location)
                        chain.proceed(args)
                    }
                )
                count++
            }
        }
        return count
    }
}
