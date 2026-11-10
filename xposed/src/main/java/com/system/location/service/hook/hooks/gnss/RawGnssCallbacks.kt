package com.system.location.service.hook.hooks.gnss

import android.os.IInterface
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.onceHookAllMethod
import io.github.libxposed.api.XposedInterface

/** Retains caller routing while Android owns the actual raw registrations and permissions. */
internal object RawGnssCallbacks {
    private val registry = GnssCallbackRegistry()

    fun proceed(chain: XposedInterface.Chain,
        install: (IInterface) -> Unit = ::installRestored,
        callerUid: () -> Int = BinderUtils::getCallerUid): Any? {
        val listener = chain.args.filterIsInstance<IInterface>().firstOrNull() ?: return chain.proceed()
        val binder = listener.asBinder()
        // Serialize duplicate add/remove for one transport without locking callback delivery.
        return synchronized(binder) {
            if (chain.executable.name.startsWith("remove")) {
                val result = chain.proceed()
                if (result != false) registry.unregister(listener)
                result
            } else {
                val existed = registry.listeners().any { it.asBinder() == binder }
                registry.register(listener, chain.args.filterIsInstance<String>().firstOrNull(), callerUid())
                try {
                    // Hook setup can fail after installing only part of the callback surface.
                    // The provisional subscription must roll back before Android is ever called.
                    install(listener)
                    val result = chain.proceed()
                    if (result == false && !existed) registry.unregister(listener)
                    result
                } catch (failure: Throwable) {
                    if (!existed) registry.unregister(listener)
                    throw failure
                }
            }
        }
    }

    fun blocked(listener: Any?): Boolean {
        val context = GnssReceiverRuntime.activeContext() ?: return false
        if (context.state.configuration.allowPhysicalRawGnss) return false
        val route = registry.routeFor(listener, context.state.routing) ?: return false
        return GnssReceiverRuntime.frameForRoute(route, context) != null
    }

    fun clear() = registry.clear()
    internal fun subscriptions() = registry.subscriptions()
    internal fun restore(subscription: com.system.location.service.hook.runtime.CallbackSubscription): Boolean =
        registry.restore(subscription)
    internal fun installRestored(listener: IInterface) {
        val hook = rawGnssCallbackHook()
        for (name in RAW_CALLBACKS) listener.javaClass.onceHookAllMethod(name, hook)
    }
}

private val RAW_PAYLOADS = setOf("onGnssMeasurementsReceived", "onGnssNavigationMessageReceived", "onGnssAntennaInfoReceived")
private val RAW_CALLBACKS = RAW_PAYLOADS + "onStatusChanged"

/** Evaluate live policy on every callback, including listeners registered while simulation was off. */
internal fun rawGnssCallbackHook(
    blocked: (Any?) -> Boolean = RawGnssCallbacks::blocked,
): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
    if (!blocked(chain.thisObject)) return@Hooker chain.proceed()
    when {
        chain.executable.name in RAW_PAYLOADS -> null
        chain.executable.name == "onStatusChanged" && chain.args.size == 1 && chain.args[0] is Int ->
            // Measurements and navigation use STATUS_NOT_SUPPORTED=0. Never report READY with no data.
            chain.proceed(arrayOf<Any>(0))
        else -> chain.proceed()
    }
}
