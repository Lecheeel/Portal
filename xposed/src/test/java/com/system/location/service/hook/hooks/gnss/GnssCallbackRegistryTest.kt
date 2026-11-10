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
    @Test fun submissionDeathOnlyUnlinksTheRegistrationThatSubmittedIt() {
        for (batch in listOf(false, true)) for (replace in listOf(false, true)) {
            val transport = Transport()
            val registry = GnssCallbackRegistry()
            val listener = transport.listener()
            registry.register(listener, "old", 10001)
            val failSubmission = {
                if (replace) {
                    registry.unregister(listener)
                    assertTrue(registry.register(listener, "new", 10002))
                }
                throw android.os.DeadObjectException()
            }
            assertThrows(android.os.DeadObjectException::class.java) {
                if (batch) registry.deliverBatch(listener, "epoch", 3, { true }) { failSubmission() }
                else registry.deliverOnce(listener, "epoch") { failSubmission() }
            }
            if (replace) {
                assertEquals(10002, registry.subscriptions().single().uid)
                assertNull(registry.subscriptions().single().lastDeliveryKey)
            } else assertTrue(registry.subscriptions().isEmpty())
            registry.clear()
            assertEquals(transport.links, transport.unlinks)
        }
    }

    @Test fun successfulFrameIsNotRepeatedAndCursorSurvivesRetirement() {
        val transport = Transport()
        val old = GnssCallbackRegistry()
        val listener = transport.listener()
        old.register(listener, "app", 10001)
        var calls = 0
        repeat(3) { old.deliverOnce(listener, "epoch-a exact output") { calls++; true } }
        assertEquals(1, calls)
        val saved = old.subscriptions().single()
        old.clear()
        val successor = GnssCallbackRegistry()
        assertTrue(successor.restore(saved))
        successor.deliverOnce(listener, "epoch-a exact output") { calls++; true }
        assertEquals(1, calls)
        successor.deliverOnce(listener, "epoch-a changed output") { calls++; true }
        successor.deliverOnce(listener, "epoch-b exact output") { calls++; true }
        assertEquals(3, calls)
        successor.unregister(listener)
        successor.register(listener, "app", 10001)
        successor.deliverOnce(listener, "epoch-b exact output") { calls++; true }
        assertEquals(4, calls)
        successor.clear()
    }

    @Test fun partialOrFailedDeliveryDoesNotCommitAndOldInFlightCannotAffectReregistration() {
        val transport = Transport()
        val registry = GnssCallbackRegistry()
        val listener = transport.listener()
        registry.register(listener, "app", 10001)
        registry.deliverOnce(listener, "frame") { false }
        assertNull(registry.subscriptions().single().lastDeliveryKey)
        assertThrows(IllegalStateException::class.java) {
            registry.deliverOnce(listener, "frame") { error("Binder failed") }
        }
        assertNull(registry.subscriptions().single().lastDeliveryKey)
        registry.deliverOnce(listener, "frame") {
            registry.unregister(listener)
            registry.register(listener, "app", 10001)
            true
        }
        assertNull(registry.subscriptions().single().lastDeliveryKey)
        registry.deliverOnce(listener, "frame") { true }
        assertEquals("frame", registry.subscriptions().single().lastDeliveryKey)
        registry.clear()
    }

    @Test fun deathDuringLinkCannotReportASuccessfulRegistrationOrRestore() {
        for (restoring in listOf(false, true)) {
            val transport = Transport().apply { dieDuringLink = true }
            val registry = GnssCallbackRegistry()
            val listener = transport.listener()
            val registered = if (restoring) registry.restore(
                com.system.location.service.hook.runtime.CallbackSubscription(listener, 10001, "app"))
            else registry.register(listener, "app", 10001)
            assertFalse(registered)
            assertTrue(registry.listeners().isEmpty())
            assertEquals(transport.links, transport.unlinks)
        }
    }

    @Test fun delayedDeathFromReplacedRegistrationCannotRemoveCurrentListener() {
        val transport = Transport()
        val registry = GnssCallbackRegistry()
        val listener = transport.listener()
        assertTrue(registry.register(listener, "old", 10001))
        val oldDeath = transport.death!!
        registry.unregister(listener)
        assertTrue(registry.register(listener, "new", 10002))
        oldDeath.binderDied()
        assertEquals(10002, registry.subscriptions().single().uid)
        registry.clear()
        assertEquals(transport.links, transport.unlinks)
    }

    @Test fun retirementUnlinksOldGenerationAndRestoresOnlyOneSuccessorDeathLink() {
        val transport = Transport()
        val old = GnssCallbackRegistry()
        val successor = GnssCallbackRegistry()
        assertTrue(old.register(transport.listener(), "app", 10001))
        val saved = old.subscriptions().single()
        val oldDeath = transport.death!!
        old.clear()
        assertTrue(old.listeners().isEmpty())
        assertEquals(1, transport.unlinks)
        assertTrue(successor.restore(saved))
        assertFalse(successor.restore(saved))
        oldDeath.binderDied()
        assertEquals(1, successor.listeners().size)
        successor.clear()
        assertEquals(2, transport.links)
        assertEquals(transport.links, transport.unlinks)
    }

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
        var dieDuringLink = false
        var links = 0
        var unlinks = 0
        var death: IBinder.DeathRecipient? = null
        private val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "linkToDeath" -> {
                    links++; death = args!![0] as IBinder.DeathRecipient
                    if (dieDuringLink) { alive = false; death!!.binderDied() }
                    null
                }
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
