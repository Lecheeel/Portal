package com.system.location.service.hook.hooks.nmea

import android.os.IBinder
import android.os.IInterface
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.hook.hooks.ListenerRouting
import com.system.location.service.hook.utils.BinderUtils
import java.util.concurrent.CopyOnWriteArrayList
import java.util.WeakHashMap

/** P5-02: maps NMEA callback objects to their registration caller for per-app fixed points. */
object NmeaListenerRouting {
    private data class Caller(val packageName: String?, val uid: Int)

    private val callers = WeakHashMap<IBinder, Caller>()
    private val active = CopyOnWriteArrayList<IInterface>()

    @Synchronized
    fun register(listener: IInterface, packageName: String?) {
        val uid = BinderUtils.getCallerUid()
        if (uid > 0) callers[listener.asBinder()] = Caller(packageName, uid)
        active.add(listener)
        com.system.location.service.hook.runtime.HookRuntime.log(
            "NMEA listener registered: ${listener.javaClass.name} active=${active.size}"
        )
        com.system.location.service.hook.hooks.gnss.GnssSignalInjection.ensureStarted()
    }

    @Synchronized
    fun unregister(listener: IInterface) {
        callers.remove(listener.asBinder())
        active.remove(listener)
    }

    @Synchronized
    fun listeners(): List<IInterface> = active.toList()

    @Synchronized
    fun fixedPointFor(listener: Any?): Wgs84? {
        val binder = (listener as? IInterface)?.asBinder() ?: return null
        val caller = callers[binder] ?: return null
        return ListenerRouting.fixedPointFor(caller.packageName, caller.uid)
    }
}
