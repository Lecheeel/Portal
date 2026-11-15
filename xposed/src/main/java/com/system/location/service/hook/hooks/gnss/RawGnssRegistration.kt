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
    rejectRawGnssRegistration(method, chain.args.filterIsInstance<android.os.IInterface>().firstOrNull())
}

internal fun rejectRawGnssRegistration(method: Method, listener: Any?): Any? {
    reportRawGnssUnsupported(listener)
    return rawGnssRegistrationResult(method, accepted = false)
}

internal fun rawGnssRegistrationResult(method: Method, accepted: Boolean): Any? {
    // Some OEM entry points return void and use status callbacks for rejection.
    return if (method.returnType == Boolean::class.javaPrimitiveType ||
        method.returnType == Boolean::class.javaObjectType) accepted else null
}

private fun rawGnssCallerBlocked(args: List<Any?>): Boolean {
    val context = GnssReceiverRuntime.protectionContext() ?: return false
    gnssCallerRoute(args.filterIsInstance<String>().firstOrNull(), BinderUtils.getCallerUid(), context.state.routing)
        ?: return false
    return rawGnssPayloadProtectionApplies(context.state.configuration.enabled, protectedRoute = true)
}

internal fun rawGnssPayloadProtectionApplies(simulationEnabled: Boolean, protectedRoute: Boolean): Boolean =
    simulationEnabled && protectedRoute

/** Report unsupported because a rejected registration cannot provide measurement callbacks. */
internal fun reportRawGnssUnsupported(listener: Any?) {
    val callback = listener ?: return
    val status = callback.javaClass.methods.firstOrNull { method ->
        method.name == "onStatusChanged" && method.parameterTypes.toList() == listOf(Int::class.javaPrimitiveType)
    } ?: return
    runCatching { status.invoke(callback, RAW_GNSS_STATUS_NOT_SUPPORTED) }
}

private const val RAW_GNSS_STATUS_NOT_SUPPORTED = 0
