package com.system.location.service.hook.hooks.gnss

import android.os.IBinder
import android.os.IInterface
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingTable
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.runtime.CallbackSubscription

/** Strong callbacks are bounded by Binder lifetime, explicit unregister and module teardown. */
internal class GnssCallbackRegistry(
    private val callerDecision: (String?, Int, RoutingConfig?) -> RoutingDecision = ::gnssCallerDecision,
) {
    private data class Entry(val listener: IInterface, val packageName: String?, val uid: Int,
        val death: IBinder.DeathRecipient, val token: Any, var lastDeliveryKey: String? = null)
    private val entries = LinkedHashMap<IBinder, Entry>()

    @Synchronized
    fun register(listener: IInterface, packageName: String?, uid: Int): Boolean {
        val binder = listener.asBinder()
        if (entries.containsKey(binder)) return true
        if (!binder.isBinderAlive) return false
        val token = Any()
        val death = IBinder.DeathRecipient { unregisterBinder(binder, token) }
        val entry = Entry(listener, packageName, uid, death, token)
        entries[binder] = entry
        try {
            binder.linkToDeath(death, 0)
        } catch (_: android.os.RemoteException) {
            unregisterBinder(binder, token)
            return false
        }
        if (entries[binder] !== entry || !binder.isBinderAlive) { unregisterBinder(binder, token); return false }
        return true
    }

    fun unregister(listener: IInterface) = unregisterBinder(listener.asBinder())

    @Synchronized
    fun subscriptions(): List<CallbackSubscription> = entries.values.map {
        CallbackSubscription(it.listener, it.uid, it.packageName, lastDeliveryKey = it.lastDeliveryKey)
    }

    @Synchronized
    fun restore(subscription: CallbackSubscription): Boolean {
        val binder = subscription.listener.asBinder()
        if (!binder.isBinderAlive || entries.containsKey(binder)) return false
        val token = Any()
        val death = IBinder.DeathRecipient { unregisterBinder(binder, token) }
        val entry = Entry(subscription.listener, subscription.packageName, subscription.uid, death, token,
            subscription.lastDeliveryKey)
        entries[binder] = entry
        return try {
            binder.linkToDeath(death, 0)
            if (entries[binder] !== entry || !binder.isBinderAlive) { unregisterBinder(binder, token); false } else true
        } catch (_: android.os.RemoteException) {
            unregisterBinder(binder, token)
            false
        }
    }

    @Synchronized
    private fun unregisterBinder(binder: IBinder, expectedToken: Any? = null) {
        val entry = entries[binder] ?: return
        if (expectedToken != null && entry.token !== expectedToken) return
        entries.remove(binder)
        runCatching { binder.unlinkToDeath(entry.death, 0) }
    }

    @Synchronized
    fun listeners(): List<IInterface> {
        entries.keys.filter { !it.isBinderAlive }.toList().forEach { unregisterBinder(it) }
        return entries.values.map { it.listener }
    }

    @Synchronized
    fun clear() { entries.keys.toList().forEach { unregisterBinder(it) } }

    /** The single delivery worker commits only complete successful frames; no lock spans Binder. */
    fun deliverOnce(listener: IInterface, key: String, action: () -> Boolean) {
        val binder = listener.asBinder()
        val entry = synchronized(this) { entries[binder]?.takeUnless { it.lastDeliveryKey == key } } ?: return
        if (!action()) return
        synchronized(this) { if (entries[binder] === entry) entry.lastDeliveryKey = key }
    }

    fun routeFor(listener: Any?, routing: RoutingConfig? = FakeLoc.runtimeSnapshots.current().routing): GnssFrameRoute? {
        val entry = entry(listener) ?: return null
        return receiverRouteFor(callerDecision(entry.packageName, entry.uid, routing))
    }

    @Synchronized
    private fun entry(listener: Any?): Entry? = (listener as? IInterface)?.asBinder()?.let(entries::get)
}

internal fun gnssCallerRoute(packageName: String?, uid: Int,
    routing: RoutingConfig? = FakeLoc.runtimeSnapshots.current().routing): GnssFrameRoute? =
    receiverRouteFor(gnssCallerDecision(packageName, uid, routing))

private fun receiverRouteFor(decision: RoutingDecision): GnssFrameRoute? = when (decision) {
    RoutingDecision.Global -> GnssFrameRoute.Global
    RoutingDecision.Deny -> null
    is RoutingDecision.Point -> GnssFrameRoute.FixedPoint(decision.point)
    is RoutingDecision.Scenario -> GnssFrameRoute.Scenario(decision.scenarioId)
}

private fun gnssCallerDecision(packageName: String?, uid: Int, routing: RoutingConfig?): RoutingDecision {
    val config = routing ?: return RoutingDecision.Global
    val packages = packageName?.takeIf { it.isNotBlank() }?.let(::listOf)
        ?: runCatching { BinderUtils.getUidPackageNames(uid = uid)?.toList().orEmpty() }.getOrDefault(emptyList())
    val system = packages.any { runCatching { BinderUtils.isSystemPackages(it) }.getOrDefault(false) }
    return RoutingTable(config).decision(uid, packages, system)
}
