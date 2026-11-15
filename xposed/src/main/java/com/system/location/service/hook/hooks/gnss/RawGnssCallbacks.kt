package com.system.location.service.hook.hooks.gnss

import android.os.IInterface
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.onceHook
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
                    install(listener)
                } catch (_: Exception) {
                    val method = chain.executable as? Method ?: return@synchronized null
                    if (existed) return@synchronized rawGnssRegistrationResult(method, accepted = true)
                    registry.unregister(listener)
                    return@synchronized rejectRawGnssRegistration(method, listener)
                }
                try {
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
        val context = GnssReceiverRuntime.protectionContext() ?: return false
        registry.routeFor(listener, context.state.routing) ?: return false
        return rawGnssPayloadProtectionApplies(context.state.configuration.enabled, protectedRoute = true)
    }

    fun clear() = registry.clear()
    internal fun subscriptions() = registry.subscriptions()
    internal fun restore(subscription: com.system.location.service.hook.runtime.CallbackSubscription): Boolean =
        registry.restore(subscription)
    internal fun restoreAndInstall(
        subscription: com.system.location.service.hook.runtime.CallbackSubscription,
        install: (IInterface) -> Unit = ::installRestored,
    ): Boolean {
        if (!registry.restore(subscription)) return false
        return try {
            install(subscription.listener)
            true
        } catch (failure: Exception) {
            Logger.error("Failed to restore raw GNSS callback hooks for ${subscription.listener.javaClass.name}", failure)
            false
        } catch (failure: LinkageError) {
            Logger.error("Raw GNSS callback API is unavailable for ${subscription.listener.javaClass.name}", failure)
            false
        }
    }
    internal fun installRestored(listener: IInterface) {
        val hook = rawGnssCallbackHook()
        val methods = rawGnssCallbackMethods(listener.javaClass)
        methods.forEach { it.onceHook(hook) }
    }
}

private val RAW_PAYLOADS = setOf("onGnssMeasurementsReceived", "onGnssNavigationMessageReceived", "onGnssAntennaInfoReceived")
private val RAW_CALLBACKS = RAW_PAYLOADS + "onStatusChanged"

internal fun rawGnssCallbackMethods(type: Class<*>): List<Method> {
    val methods = LinkedHashSet<Method>()
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
        current.declaredMethods
            .filterTo(methods) { it.name in RAW_CALLBACKS && !Modifier.isAbstract(it.modifiers) }
        current = current.superclass
    }
    require(methods.any { it.name in RAW_PAYLOADS }) {
        "Unsupported raw GNSS callback implementation: ${type.name}"
    }
    return methods.toList()
}

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
