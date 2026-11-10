package com.system.location.service.hook.hooks.gnss

import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingTable
import com.system.location.service.hook.utils.BinderUtils

/** Raw observations are either real receiver data or explicitly unsupported, never fabricated. */
internal fun rawGnssRegistrationHook(
    enabled: () -> Boolean = { FakeLoc.enable && FakeLoc.enableMockGnss },
    allowPhysical: () -> Boolean = { FakeLoc.allowPhysicalRawGnss },
    hasFrame: () -> Boolean = { GnssReceiverRuntime.currentFrame() != null },
    appliesToCaller: (List<Any?>) -> Boolean = ::rawGnssRouteApplies,
): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
    val method = chain.executable as? Method ?: return@Hooker chain.proceed()
    // Removal must always reach Android, including listeners created before simulation started.
    if (!method.name.startsWith("add") || chain.args.isEmpty() ||
        !enabled() || allowPhysical() || !hasFrame() || !appliesToCaller(chain.args)) return@Hooker RawGnssCallbacks.proceed(chain)
    if (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.javaObjectType) {
        false
    } else {
        throw UnsupportedOperationException("Raw GNSS simulation is unavailable; physical receiver data is disabled")
    }
}

private fun rawGnssRouteApplies(args: List<Any?>): Boolean {
    val config = FakeLoc.routingConfig ?: return true
    val uid = BinderUtils.getCallerUid()
    val packages = args.filterIsInstance<String>().firstOrNull()?.let(::listOf)
        ?: runCatching { BinderUtils.getUidPackageNames(uid = uid)?.toList().orEmpty() }.getOrDefault(emptyList())
    val system = packages.any { runCatching { BinderUtils.isSystemPackages(it) }.getOrDefault(false) }
    return RoutingTable(config).decision(uid, packages, system) !is RoutingDecision.Deny
}
