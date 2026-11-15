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
                val result = try {
                    chain.proceed()
                } catch (failure: UnsupportedOperationException) {
                    // Removal must never turn an unsupported OEM surface into an app crash.
                    return@synchronized rawGnssRegistrationResult(
                        chain.executable as? Method ?: return@synchronized null,
                        accepted = false,
                    )
                }
                // The app asked to remove this transport. Always drop our routing record even
                // when a synthetic-only registration never reached the framework and its remove
                // returns false.
                registry.unregister(listener)
                result
            } else {
                val existed = registry.listeners().any { it.asBinder() == binder }
                registry.register(listener, chain.args.filterIsInstance<String>().firstOrNull(), callerUid())
                try {
                    // Hook setup can fail after installing only part of the callback surface.
                    install(listener)
                } catch (_: Exception) {
                    return@synchronized rejectInstallation(chain, listener, existed)
                } catch (_: LinkageError) {
                    // A callback method can be present but not hookable on an OEM runtime. Treat it
                    // like a normal rejected registration instead of letting an Error reach Binder.
                    return@synchronized rejectInstallation(chain, listener, existed)
                }
                try {
                    val result = chain.proceed()
                    if (result == false && !existed) registry.unregister(listener)
                    result
                } catch (failure: UnsupportedOperationException) {
                    // The framework itself may report an unsupported raw surface. Report the API's
                    // unsupported status instead of propagating an exception into the caller.
                    val method = chain.executable as? Method ?: return@synchronized null
                    if (existed) return@synchronized rawGnssRegistrationResult(method, accepted = true)
                    registry.unregister(listener)
                    rejectRawGnssRegistration(method, listener)
                } catch (failure: Throwable) {
                    if (!existed) registry.unregister(listener)
                    throw failure
                }
            }
        }
    }

    /**
     * Synthetic-only registration: Android may have no raw-measurement capability at all, so the
     * framework must not be asked to start a physical reader. The app-visible API still returns
     * its normal accepted result and the producer owns every callback.
     */
    fun registerSynthetic(
        chain: XposedInterface.Chain,
        install: (IInterface) -> Unit = ::installRestored,
        callerUid: () -> Int = BinderUtils::getCallerUid,
    ): Any? {
        val listener = chain.args.filterIsInstance<IInterface>().firstOrNull() ?: return null
        val binder = listener.asBinder()
        return synchronized(binder) {
            val method = chain.executable as? Method ?: return@synchronized null
            if (!registry.register(listener, chain.args.filterIsInstance<String>().firstOrNull(), callerUid())) {
                return@synchronized rawGnssRegistrationResult(method, accepted = false)
            }
            try {
                install(listener)
            } catch (_: Exception) {
                return@synchronized rejectInstallation(chain, listener, existed = false)
            } catch (_: LinkageError) {
                return@synchronized rejectInstallation(chain, listener, existed = false)
            }
            rawGnssRegistrationResult(method, accepted = true)
        }
    }

    private fun rejectInstallation(
        chain: XposedInterface.Chain,
        listener: IInterface,
        existed: Boolean,
    ): Any? {
        val method = chain.executable as? Method ?: return null
        if (existed) return rawGnssRegistrationResult(method, accepted = true)
        registry.unregister(listener)
        return rejectRawGnssRegistration(method, listener)
    }

    fun blocked(listener: Any?): Boolean {
        val context = GnssReceiverRuntime.protectionContext() ?: return false
        registry.routeFor(listener, context.state.routing) ?: return false
        return rawGnssPayloadProtectionApplies(context.state.configuration.enabled, protectedRoute = true)
    }

    fun listeners(): List<IInterface> = registry.listeners()

    fun routeFor(listener: Any?, routing: com.system.location.service.core.routing.RoutingConfig?):
        com.system.location.service.core.gnss.GnssFrameRoute? = registry.routeFor(listener, routing)

    fun contains(listener: Any?): Boolean {
        val binder = (listener as? IInterface)?.asBinder() ?: return false
        return registry.listeners().any { it.asBinder() == binder }
    }

    /** Synthetic delivery bypasses the framework callback hook via invokeOriginalMethod. */
    fun deliverMeasurements(listener: IInterface, key: String, action: () -> Boolean) =
        registry.deliverOnce(listener, key, action)

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

private val RAW_PAYLOADS = setOf(
    "onGnssMeasurementsReceived",
    "onGnssNavigationMessageReceived",
    "onGnssAntennaInfoReceived",
    // Binder-side IGnssAntennaInfoListener name; the app-side callback uses the older name above.
    "onGnssAntennaInfoChanged",
)
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
