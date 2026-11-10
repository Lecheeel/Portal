package com.system.location.service.hook

import android.os.IBinder
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.runtime.RuntimeTransfer
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimePeerRegistryTest {
    private val publication = RuntimePeerRegistry.Publication(1, RuntimeTransfer(RuntimeSnapshot(), false))

    @Test fun replacesSameServiceDeduplicatesBindersBoundsCapacityAndCleansDeath() {
        val registry = RuntimePeerRegistry(1, { _, _, _ -> }, { throw AssertionError(it) })
        val first = Transport()
        val second = Transport()
        try {
            assertTrue(registry.register(1001, "phone", "a", first.binder))
            assertFalse(registry.register(1002, "fused", "b", second.binder))
            assertTrue(registry.register(1001, "phone", "b", second.binder))
            assertEquals(1, first.unlinks)
            first.death!!.binderDied()
            assertEquals(1, registry.size())
            assertTrue(registry.register(1001, "other-name", "c", second.binder))
            assertEquals(1, registry.size())
            second.alive = false
            second.death!!.binderDied()
            assertEquals(0, registry.size())
            assertFalse(registry.register(1001, "phone", "d", second.binder))
        } finally { registry.clear() }
    }

    @Test fun stalledPeerDoesNotHoldRegistryLockOrBlockOtherPeersAndOldFailureCannotRemoveReplacement() {
        val blocked = Transport()
        val fast = Transport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val registry = RuntimePeerRegistry(2, { binder, _, _ ->
            if (binder == blocked.binder) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                error("old transport failed")
            } else delivered.countDown()
        }, { failed.countDown() })
        try {
            registry.register(1001, "phone", "a", blocked.binder)
            registry.offer(publication)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(registry.register(1002, "fused", "b", fast.binder))
            registry.offer(publication.copy(sequence = 2))
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertTrue(registry.register(1001, "phone", "c", Transport().binder))
            release.countDown()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(2, registry.size())
        } finally { release.countDown(); registry.clear() }
    }

    @Test fun subscriptionsCanBeRecreatedWithOriginalUidNameSessionAndBinder() {
        val first = RuntimePeerRegistry(2, { _, _, _ -> }, { throw AssertionError(it) })
        val restored = RuntimePeerRegistry(2, { _, _, _ -> }, { throw AssertionError(it) })
        val transport = Transport()
        try {
            assertTrue(first.register(1001, "phone", "session-a", transport.binder))
            val row = first.subscriptions().single()
            assertEquals(1001, row.uid)
            assertEquals("phone", row.name)
            assertEquals("session-a", row.session)
            assertSame(transport.binder, row.binder)
            val oldDeath = transport.death!!
            first.clear()
            assertEquals(1, transport.unlinks)
            assertEquals(0, first.size())
            assertTrue(restored.restore(row))
            oldDeath.binderDied()
            assertEquals(1, restored.size())
        } finally { first.clear(); restored.clear() }
        assertEquals(2, transport.unlinks)
    }

    private class Transport {
        @Volatile var alive = true
        var unlinks = 0
        var death: IBinder.DeathRecipient? = null
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "linkToDeath" -> { death = args!![0] as IBinder.DeathRecipient; null }
                "unlinkToDeath" -> { unlinks++; true }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as IBinder
    }
}
