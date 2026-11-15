package com.system.location.service.hook.hooks.gnss

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import com.system.location.service.core.gnss.RawGnssSynthesis
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.LocationCallerGuard
import com.system.location.service.hook.utils.Logger

/** Raw observations are either real receiver data or explicitly unsupported, never fabricated. */
internal fun rawGnssRegistrationHook(
    blocked: (List<Any?>) -> Boolean = ::rawGnssCallerBlocked,
): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
    try {
        handleRawGnssRegistration(chain, blocked)
    } catch (failure: SecurityException) {
        throw failure
    } catch (failure: IllegalStateException) {
        // A genuine framework state failure remains the framework's result.
        throw failure
    } catch (failure: Throwable) {
        // A raw-registration hook must never become a new crash surface. Policy/resource/OEM
        // failures are reported as the API's unsupported result instead of crossing Binder.
        val method = chain.executable as? Method
        Logger.error("raw GNSS registration hook failed: ${method?.name}", failure)
        val listener = chain.args.filterIsInstance<android.os.IInterface>().firstOrNull()
        if (method != null) {
            reportRawGnssUnsupported(listener)
            rawGnssRegistrationResult(method, accepted = false)
        } else null
    }
}

private fun handleRawGnssRegistration(
    chain: XposedInterface.Chain,
    blocked: (List<Any?>) -> Boolean,
): Any? {
    val method = chain.executable as? Method ?: return chain.proceed()
    // Removal must always reach Android, including listeners created before simulation started.
    if (!method.name.startsWith("add") || chain.args.isEmpty() ||
        !blocked(chain.args)) return RawGnssCallbacks.proceed(chain)
    // Let a caller without location permission observe the framework's SecurityException.
    if (!LocationCallerGuard.canReadLocation()) return RawGnssCallbacks.proceed(chain)
    val listener = chain.args.filterIsInstance<android.os.IInterface>().firstOrNull()
    val syntheticMeasures = method.name.contains("Measurements") && runCatching {
        GnssReceiverRuntime.protectionContext()?.let {
            RawGnssSynthesis.canSynthesize(it.state.configuration, System.currentTimeMillis())
        } == true
    }.getOrDefault(false)
    if (syntheticMeasures) {
        // The HAL may expose no raw-measurement capability at all. Register only in our routing
        // registry: the app-visible API still succeeds, while the producer owns every payload.
        val result = RawGnssCallbacks.registerSynthetic(chain)
        if (RawGnssCallbacks.contains(listener)) {
            // READY is emitted by the producer only after the first real batch is constructed;
            // reporting it here would claim a working reader before any payload exists and would
            // make AOSP's one-shot status contract observable as two READY callbacks.
            runCatching { GnssSignalInjection.ensureStarted() }
        }
        return result
    }
    return rejectRawGnssRegistration(method, listener)
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
internal fun reportRawGnssUnsupported(listener: Any?) = reportRawGnssStatus(listener, RAW_GNSS_STATUS_NOT_SUPPORTED)

/** The synthetic observation producer owns the payload, so the reader can report READY. */
internal fun reportRawGnssReady(listener: Any?) = reportRawGnssStatus(listener, RAW_GNSS_STATUS_READY)

private fun reportRawGnssStatus(listener: Any?, code: Int) {
    val callback = listener ?: return
    val status = callback.javaClass.methods.firstOrNull { method ->
        method.name == "onStatusChanged" && method.parameterTypes.toList() == listOf(Int::class.javaPrimitiveType)
    } ?: return
    // The callback proxy's onStatusChanged is itself guarded by rawGnssCallbackHook (which
    // suppresses real framework payloads). Invoke the original transport directly so a synthetic
    // READY is not rewritten to NOT_SUPPORTED by that guard.
    runCatching { HookRuntime.invokeOriginalMethod(status, callback, arrayOf(code)) }
        .recoverCatching { status.invoke(callback, code) }
}

private const val RAW_GNSS_STATUS_NOT_SUPPORTED = 0
private const val RAW_GNSS_STATUS_READY = 1
