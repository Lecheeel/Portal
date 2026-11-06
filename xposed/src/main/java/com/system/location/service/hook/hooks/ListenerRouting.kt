package com.system.location.service.hook.hooks

import android.os.IBinder
import com.system.location.service.core.routing.CallerRouter
import com.system.location.service.core.routing.PackageUidLookup
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.routing.RoutingMode
import com.system.location.service.core.routing.RoutingTable
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import java.util.concurrent.ConcurrentHashMap

/**
 * P5-02 listener-side routing.
 *
 * The UID is captured at registration because Binder.getCallingUid() is no longer the app when the
 * callback runs. Routing mode only changes behavior for PER_APP configs; default/global configs
 * keep the current fake fix for every listener. Denied packages stop receiving fake updates; a
 * matched scenario still uses the single active fake until the multi-scenario store lands.
 */
object ListenerRouting {
    private val callerUids = ConcurrentHashMap<IBinder, Int>()
    private var cachedConfig: RoutingConfig? = null
    private var cachedRouter: CallerRouter? = null

    fun register(listener: android.os.IInterface) {
        val uid = BinderUtils.getCallerUid()
        if (uid > 0) callerUids[listener.asBinder()] = uid
    }

    fun unregister(listener: android.os.IInterface) {
        callerUids.remove(listener.asBinder())
    }

    fun unregisterBinder(binder: IBinder) {
        callerUids.remove(binder)
    }

    fun shouldDeliver(listener: android.os.IInterface): Boolean = route(listener) !is Route.Denied

    fun route(listener: android.os.IInterface): Route {
        val config = FakeLoc.routingConfig ?: return Route.Default
        if (config.mode == RoutingMode.GLOBAL) return Route.Default
        val uid = callerUids[listener.asBinder()] ?: return Route.Default
        if (uid < 10_000) return Route.Default
        val router = routerFor(config) ?: return Route.Default
        return when (val decision = router.decision(uid)) {
            RoutingDecision.Deny -> Route.Denied
            is RoutingDecision.Scenario -> Route.Scenario(decision.scenarioId)
            RoutingDecision.Global -> Route.Default
        }
    }

    sealed class Route {
        data object Default : Route()
        data object Denied : Route()
        data class Scenario(val scenarioId: String) : Route()
    }

    @Synchronized
    private fun routerFor(config: RoutingConfig): CallerRouter? {
        if (cachedConfig !== config) {
            cachedRouter = runCatching { CallerRouter(RoutingTable(config), Lookup) }.getOrNull()
            cachedConfig = config
        }
        return cachedRouter
    }

    private object Lookup : PackageUidLookup {
        override fun packagesForUid(uid: Int): List<String> =
            runCatching { BinderUtils.getUidPackageNames(uid = uid)?.toList().orEmpty() }.getOrDefault(emptyList())

        override fun isSystemPackage(packageName: String): Boolean =
            runCatching { BinderUtils.isSystemPackages(packageName) }.getOrDefault(false)
    }
}
