package com.system.location.service.hook.hooks.gnss

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.LocationCallerGuard

/** Raw observations are either real receiver data or explicitly unsupported, never fabricated. */
internal fun rawGnssRegistrationHook(
    blocked: (List<Any?>) -> Boolean = ::rawGnssCallerBlocked,
): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
    val method = chain.executable as? Method ?: return@Hooker chain.proceed()
    // Removal must always reach Android, including listeners created before simulation started.
    if (!method.name.startsWith("add") || chain.args.isEmpty() ||
        !blocked(chain.args)) return@Hooker RawGnssCallbacks.proceed(chain)
    // Let a caller without location permission observe the framework's SecurityException.
    if (!LocationCallerGuard.canReadLocation()) return@Hooker RawGnssCallbacks.proceed(chain)
    if (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.javaObjectType) {
        false
    } else {
        throw UnsupportedOperationException("Raw GNSS simulation is unavailable; physical receiver data is disabled")
    }
}

private fun rawGnssCallerBlocked(args: List<Any?>): Boolean {
    val context = GnssReceiverRuntime.activeContext() ?: return false
    if (context.state.configuration.allowPhysicalRawGnss) return false
    val route = gnssCallerRoute(args.filterIsInstance<String>().firstOrNull(), BinderUtils.getCallerUid(), context.state.routing)
        ?: return false
    return GnssReceiverRuntime.frameForRoute(route, context) != null
}
