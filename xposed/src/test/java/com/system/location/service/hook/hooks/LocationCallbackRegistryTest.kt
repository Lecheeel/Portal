package com.system.location.service.hook.hooks

import android.os.IBinder
import android.os.IInterface
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LocationCallbackRegistryTest {
    @Test fun retirementReleasesOldDeathLinkWhileSavedProvidersRestoreInSuccessor() {
        val transport = Transport()
        val old = LocationCallbackRegistry()
        val successor = LocationCallbackRegistry()
        old.register("gps", transport.listener(), 10001)
        old.register("network", transport.listener(), 10001)
        val saved = old.subscriptions()
        val oldDeath = transport.death!!
        old.clear()
        assertEquals(1, transport.unlinks)
        assertTrue(old.listeners().isEmpty())
        assertTrue(successor.restore(saved.single()))
        assertEquals(2, transport.links)
        oldDeath.binderDied()
        assertEquals(listOf("gps", "network"), successor.listeners().map { it.first })
        assertEquals(10001, successor.subscriptions().single().uid)
        successor.clear()
        assertEquals(transport.links, transport.unlinks)
    }

    @Test fun duplicateProviderSubscriptionsShareOneDeathRecipientAndPreserveOtherProviders() {
        val transport = Transport()
        var added = 0
        val registry = LocationCallbackRegistry(added = { added++ })
        repeat(20) { assertTrue(registry.register("gps", transport.listener())) }
        assertTrue(registry.register("network", transport.listener()))
        assertEquals(listOf("gps", "network"), registry.listeners().map { it.first })
        assertEquals(1, transport.links)
        assertEquals(1, added)
        registry.unregister(transport.listener())
        assertTrue(registry.listeners().isEmpty())
        assertEquals(1, transport.unlinks)
    }

    @Test fun explicitUnregisterUnlinksAndRemovesRoutingExactlyOnce() {
        val transport = Transport()
        var removed = 0
        val registry = LocationCallbackRegistry(removed = { removed++ })
        registry.register("gps", transport.listener())
        repeat(3) { registry.unregister(transport.listener()) }
        assertTrue(registry.listeners().isEmpty())
        assertEquals(1, transport.unlinks)
        assertEquals(1, removed)
    }

    @Test fun originalZeroArgumentBinderDeathRemovesAllSubscriptionsAndRouting() {
        val transport = Transport()
        var removed = 0
        val registry = LocationCallbackRegistry(removed = { removed++ })
        registry.register("gps", transport.listener())
        registry.register("fused", transport.listener())
        transport.alive = false
        transport.death!!.binderDied()
        assertTrue(registry.listeners().isEmpty())
        assertEquals(1, transport.unlinks)
        assertEquals(1, removed)
    }

    @Test fun deathBeforeOrDuringLinkCannotLeaveAPublishedListenerOrRouting() {
        for (duringLink in listOf(false, true)) {
            val transport = Transport().apply { alive = duringLink; dieDuringLink = duringLink }
            var added = 0
            val registry = LocationCallbackRegistry(added = { added++ })
            assertFalse(registry.register("gps", transport.listener()))
            assertTrue(registry.listeners().isEmpty())
            assertEquals(0, added)
        }
    }

    @Test fun delayedOldDeathDoesNotRemoveAReplacementRegistration() {
        val transport = Transport()
        val registry = LocationCallbackRegistry()
        registry.register("gps", transport.listener())
        val oldDeath = transport.death!!
        registry.unregister(transport.listener())
        registry.register("network", transport.listener())
        oldDeath.binderDied()
        assertEquals(listOf("network"), registry.listeners().map { it.first })
        registry.clear()
        assertEquals(2, transport.unlinks)
    }

    @Test fun missedDeathIsPrunedAndClearUnlinksRemainingTransports() {
        val first = Transport()
        val second = Transport()
        var removed = 0
        val registry = LocationCallbackRegistry(removed = { removed++ })
        registry.register("gps", first.listener())
        registry.register("network", second.listener())
        first.alive = false
        assertEquals(listOf("network"), registry.listeners().map { it.first })
        registry.clear()
        assertEquals(2, removed)
        assertEquals(1, first.unlinks)
        assertEquals(1, second.unlinks)
    }

    @Test fun routingFailureRollsBackTheLinkAndRegistration() {
        val transport = Transport()
        val registry = LocationCallbackRegistry(added = { error("routing unavailable") })
        assertTrue(runCatching { registry.register("gps", transport.listener()) }.isFailure)
        assertTrue(registry.listeners().isEmpty())
        assertEquals(1, transport.unlinks)
    }

    private class Transport {
        var alive = true
        var dieDuringLink = false
        var links = 0
        var unlinks = 0
        var death: IBinder.DeathRecipient? = null
        private val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "linkToDeath" -> {
                    links++
                    death = args!![0] as IBinder.DeathRecipient
                    if (dieDuringLink) { alive = false; death!!.binderDied() }
                    null
                }
                "unlinkToDeath" -> { unlinks++; true }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "test-location-binder"
                else -> null
            }
        } as IBinder
        fun listener() = object : IInterface { override fun asBinder(): IBinder = binder }
    }
}
