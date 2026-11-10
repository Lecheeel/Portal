package com.system.location.service.hook.hooks.nmea

import android.os.IInterface
import com.system.location.service.hook.hooks.gnss.GnssCallbackRegistry
import com.system.location.service.hook.hooks.gnss.GnssSignalInjection
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.runtime.CallbackSubscription

object NmeaListenerRouting {
    private val registry = GnssCallbackRegistry()

    fun register(listener: IInterface, packageName: String?) {
        if (registry.register(listener, packageName, BinderUtils.getCallerUid())) GnssSignalInjection.ensureStarted()
    }
    fun unregister(listener: IInterface) = registry.unregister(listener)
    fun listeners(): List<IInterface> = registry.listeners()
    fun routeFor(listener: Any?, routing: RoutingConfig? = FakeLoc.runtimeSnapshots.current().routing): GnssFrameRoute? =
        registry.routeFor(listener, routing)
    fun clear() = registry.clear()
    internal fun deliverBatch(listener: IInterface, key: String, itemCount: Int,
        allowed: () -> Boolean, action: (Int) -> Unit) =
        registry.deliverBatch(listener, key, itemCount, allowed, action)
    internal fun subscriptions(): List<CallbackSubscription> = registry.subscriptions()
    internal fun restore(subscription: CallbackSubscription): Boolean = registry.restore(subscription)
}
