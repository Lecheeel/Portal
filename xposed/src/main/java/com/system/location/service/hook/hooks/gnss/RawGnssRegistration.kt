package com.system.location.service.hook.hooks.gnss

import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** Raw observations are either real receiver data or explicitly unsupported, never fabricated. */
internal fun rawGnssRegistrationHook(
    enabled: () -> Boolean = { FakeLoc.enable && FakeLoc.enableMockGnss },
    allowPhysical: () -> Boolean = { FakeLoc.enableAGPS },
    hasFrame: () -> Boolean = { GnssReceiverRuntime.currentFrame() != null },
): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
    val method = chain.executable as? Method ?: return@Hooker chain.proceed()
    // Removal must always reach Android, including listeners created before simulation started.
    if (!method.name.startsWith("add") || chain.args.isEmpty() ||
        !enabled() || allowPhysical() || !hasFrame()) return@Hooker chain.proceed()
    if (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.javaObjectType) {
        false
    } else {
        throw UnsupportedOperationException("Raw GNSS simulation is unavailable; physical receiver data is disabled")
    }
}
