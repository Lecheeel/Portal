package com.system.location.service.hook.hooks.gnss

import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import com.system.location.service.hook.utils.BinderUtils

/** Raw observations are either real receiver data or explicitly unsupported, never fabricated. */
internal fun rawGnssRegistrationHook(
    enabled: () -> Boolean = { FakeLoc.enable && FakeLoc.enableMockGnss },
    allowPhysical: () -> Boolean = { FakeLoc.allowPhysicalRawGnss },
    hasFrame: (List<Any?>) -> Boolean = ::rawGnssCallerHasFrame,
    appliesToCaller: (List<Any?>) -> Boolean = { true },
): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
    val method = chain.executable as? Method ?: return@Hooker chain.proceed()
    // Removal must always reach Android, including listeners created before simulation started.
    if (!method.name.startsWith("add") || chain.args.isEmpty() ||
        !enabled() || allowPhysical() || !appliesToCaller(chain.args) || !hasFrame(chain.args)) return@Hooker RawGnssCallbacks.proceed(chain)
    if (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.javaObjectType) {
        false
    } else {
        throw UnsupportedOperationException("Raw GNSS simulation is unavailable; physical receiver data is disabled")
    }
}

private fun rawGnssCallerHasFrame(args: List<Any?>): Boolean {
    val route = gnssCallerRoute(args.filterIsInstance<String>().firstOrNull(), BinderUtils.getCallerUid())
        ?: return false
    return GnssReceiverRuntime.frameForRoute(route) != null
}
