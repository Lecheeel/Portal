package com.system.location.service.hook.hooks.gnss

import android.os.IInterface
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.hook.utils.BinderUtils

object GnssListenerRouting {
    private val registry = GnssCallbackRegistry()

    fun register(listener: IInterface, packageName: String?) {
        if (registry.register(listener, packageName, BinderUtils.getCallerUid())) GnssSignalInjection.ensureStarted()
    }
    fun unregister(listener: IInterface) = registry.unregister(listener)
    fun listeners(): List<IInterface> = registry.listeners()
    fun fixedPointFor(listener: Any?): Wgs84? = registry.fixedPointFor(listener)
    fun shouldDeliver(listener: Any?): Boolean = registry.shouldDeliver(listener)
    fun clear() = registry.clear()
}
