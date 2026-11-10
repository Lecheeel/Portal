package com.system.location.service.hook.hooks.gnss

import android.os.IBinder
import android.os.IInterface
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class GnssCallbackRegistryTest {
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
