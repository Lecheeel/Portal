package com.system.location.service.hook.hooks.gnss

import android.os.IBinder
import android.os.IInterface
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.routing.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class GnssCallbackRegistryTest {
    @Test fun receiverRouteRetainsScenarioAndPointIdentityAcrossLiveConfigChanges() {
        val point = Wgs84(31.0, 121.0)
        var config = RoutingConfig(mode = RoutingMode.PER_APP, routes = listOf(
            AppRoute("app", scenarioId = "route-a", uid = 10001)))
        val registry = GnssCallbackRegistry { packageName, uid, captured -> RoutingTable(captured ?: RoutingConfig()).decision(uid, packageName, false) }
        val listener = Transport().listener()
        assertNull(registry.routeFor(listener, config))
        registry.register(listener, "app", 10001)
        assertEquals(GnssFrameRoute.Scenario("route-a"), registry.routeFor(listener, config))
        val retained = config
        config = config.copy(routes = listOf(AppRoute("app", point = point)))
        assertEquals(GnssFrameRoute.Scenario("route-a"), registry.routeFor(listener, retained))
        assertEquals(GnssFrameRoute.Global, registry.routeFor(listener, null))
        assertEquals(GnssFrameRoute.FixedPoint(point), registry.routeFor(listener, config))
        config = config.copy(routes = emptyList(), globalScenarioId = "route-b")
        assertEquals(GnssFrameRoute.Scenario("route-b"), registry.routeFor(listener, config))
        config = config.copy(deniedPackages = setOf("app"))
        assertNull(registry.routeFor(listener, config))
        config = config.copy(mode = RoutingMode.GLOBAL)
        assertEquals(GnssFrameRoute.Global, registry.routeFor(listener, config))
        registry.unregister(listener)
        assertNull(registry.routeFor(listener, config))
    }

    @Test fun duplicateTransportRegistrationIsDeduplicatedAndDeathReleasesTheCallback() {
        val transport = Transport()
        val registry = GnssCallbackRegistry()
        assertTrue(registry.register(transport.listener(), "app", 10001))
        assertTrue(registry.register(transport.listener(), "app", 10001))
        assertEquals(1, registry.listeners().size)
        assertEquals(1, transport.links)
        transport.alive = false
        transport.death!!.binderDied()
        assertTrue(registry.listeners().isEmpty())
        assertEquals(1, transport.unlinks)
    }

    @Test fun explicitUnregisterAndClearUnlinkRecipientsAndDeadBindersCannotRegister() {
        val transport = Transport()
        val registry = GnssCallbackRegistry()
        val listener = transport.listener()
        registry.register(listener, "app", 10001)
        registry.unregister(listener)
        assertTrue(registry.listeners().isEmpty())
        registry.register(listener, "app", 10001)
        registry.clear()
        assertEquals(2, transport.unlinks)
        transport.alive = false
        assertFalse(registry.register(listener, "app", 10001))
    }

    private class Transport {
        var alive = true
        var links = 0
        var unlinks = 0
        var death: IBinder.DeathRecipient? = null
        private val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "linkToDeath" -> { links++; death = args!![0] as IBinder.DeathRecipient; null }
                "unlinkToDeath" -> { unlinks++; true }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "test-binder"
                else -> null
            }
        } as IBinder
        fun listener() = object : IInterface { override fun asBinder(): IBinder = binder }
    }
}
