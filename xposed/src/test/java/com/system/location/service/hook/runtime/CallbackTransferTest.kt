package com.system.location.service.hook.runtime

import android.os.IBinder
import android.os.IInterface
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CallbackTransferTest {
    @Test fun transfersBinderAndOriginalIdentityWithoutRetainingOldProxy() {
        var alive = true
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "getInterfaceDescriptor" -> "android.location.ILocationListener"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as IBinder
        val old = object : IInterface { override fun asBinder() = binder }
        val replacement = object : IInterface { override fun asBinder() = binder }
        val row = CallbackSubscription(old, 10272, "receiver", listOf("gps", "network")).encode()
        assertSame(binder, row[0])
        assertFalse(row.any { it === old })
        val restored = CallbackSubscription.decode(row) { descriptor, transport ->
            assertEquals("android.location.ILocationListener", descriptor)
            assertSame(binder, transport)
            replacement
        }!!
        assertSame(replacement, restored.listener)
        assertEquals(10272, restored.uid)
        assertEquals("receiver", restored.packageName)
        assertEquals(listOf("gps", "network"), restored.providers)
        alive = false
        assertNull(CallbackSubscription.decode(row) { _, _ -> error("dead binder must not resolve") })
    }
}
