package com.system.location.service.hook.hooks.gnss

import android.os.IBinder
import android.os.IInterface
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.hook.hooks.ListenerRouting
import com.system.location.service.hook.utils.BinderUtils
import java.util.WeakHashMap

/** P5-02: maps GNSS status callback objects to their registration caller for fixed-point geometry. */
object GnssListenerRouting {
    private data class Caller(val packageName: String?, val uid: Int)

    private val callers = WeakHashMap<IBinder, Caller>()

    @Synchronized
    fun register(listener: IInterface, packageName: String?) {
        val uid = BinderUtils.getCallerUid()
        if (uid > 0) callers[listener.asBinder()] = Caller(packageName, uid)
    }

    @Synchronized
    fun unregister(listener: IInterface) {
        callers.remove(listener.asBinder())
    }

    @Synchronized
    fun fixedPointFor(listener: Any?): Wgs84? {
        val binder = (listener as? IInterface)?.asBinder() ?: return null
        val caller = callers[binder] ?: return null
        return ListenerRouting.fixedPointFor(caller.packageName, caller.uid)
    }
}
