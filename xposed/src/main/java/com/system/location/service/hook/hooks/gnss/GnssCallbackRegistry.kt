package com.system.location.service.hook.hooks.gnss

import android.os.IBinder
import android.os.IInterface
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingTable
import com.system.location.service.hook.hooks.ListenerRouting
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc

/** Strong callbacks are bounded by Binder lifetime, explicit unregister and module teardown. */
internal class GnssCallbackRegistry {
    private data class Entry(val listener: IInterface, val packageName: String?, val uid: Int,
        val death: IBinder.DeathRecipient)
    private val entries = LinkedHashMap<IBinder, Entry>()

    @Synchronized
    fun register(listener: IInterface, packageName: String?, uid: Int): Boolean {
        val binder = listener.asBinder()
        if (entries.containsKey(binder)) return true
        if (!binder.isBinderAlive) return false
        val death = IBinder.DeathRecipient { unregisterBinder(binder) }
        val entry = Entry(listener, packageName, uid, death)
        entries[binder] = entry
        try {
            binder.linkToDeath(death, 0)
        } catch (_: android.os.RemoteException) {
            entries.remove(binder)
            return false
        }
        if (!binder.isBinderAlive) { unregisterBinder(binder); return false }
        return true
    }

    fun unregister(listener: IInterface) = unregisterBinder(listener.asBinder())

    @Synchronized
    private fun unregisterBinder(binder: IBinder) {
        val entry = entries.remove(binder) ?: return
        runCatching { binder.unlinkToDeath(entry.death, 0) }
    }

    @Synchronized
    fun listeners(): List<IInterface> {
        entries.keys.filter { !it.isBinderAlive }.toList().forEach(::unregisterBinder)
        return entries.values.map { it.listener }
    }

    @Synchronized
    fun clear() { entries.keys.toList().forEach(::unregisterBinder) }

    fun fixedPointFor(listener: Any?): Wgs84? {
        val entry = entry(listener) ?: return null
        return ListenerRouting.fixedPointFor(entry.packageName, entry.uid)
    }

    fun shouldDeliver(listener: Any?): Boolean {
        val entry = entry(listener) ?: return false
        val config = FakeLoc.routingConfig ?: return true
        val packages = entry.packageName?.let(::listOf)
            ?: runCatching { BinderUtils.getUidPackageNames(uid = entry.uid)?.toList().orEmpty() }.getOrDefault(emptyList())
        val system = packages.any { runCatching { BinderUtils.isSystemPackages(it) }.getOrDefault(false) }
        return RoutingTable(config).decision(entry.uid, packages, system) !is RoutingDecision.Deny
    }

    @Synchronized
    private fun entry(listener: Any?): Entry? = (listener as? IInterface)?.asBinder()?.let(entries::get)
}
