package com.system.location.service.hook.hooks.nmea

import android.os.IInterface
import com.system.location.service.hook.hooks.gnss.GnssCallbackRegistry
import com.system.location.service.hook.hooks.gnss.GnssSignalInjection
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.hook.utils.BinderUtils

object NmeaListenerRouting {
    private val registry = GnssCallbackRegistry()

    fun register(listener: IInterface, packageName: String?) {
        if (registry.register(listener, packageName, BinderUtils.getCallerUid())) GnssSignalInjection.ensureStarted()
    }
    fun unregister(listener: IInterface) = registry.unregister(listener)
    fun listeners(): List<IInterface> = registry.listeners()
    fun routeFor(listener: Any?): GnssFrameRoute? = registry.routeFor(listener)
    fun clear() = registry.clear()
}
