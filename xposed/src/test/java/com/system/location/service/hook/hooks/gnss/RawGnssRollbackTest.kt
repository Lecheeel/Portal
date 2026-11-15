package com.system.location.service.hook.hooks.gnss

import android.os.IBinder
import android.os.IInterface
import io.github.libxposed.api.XposedInterface
import com.system.location.service.hook.runtime.CallbackSubscription
import com.system.location.service.hook.runtime.HookRuntime
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class RawGnssRollbackTest {
    private val frameworkField = HookRuntime::class.java.getDeclaredField("framework").apply {
        isAccessible = true
    }
    private var previousFramework: XposedInterface? = null

    @Before
    fun installJvmLogFramework() {
        previousFramework = frameworkField.get(HookRuntime) as? XposedInterface
        frameworkField.set(HookRuntime, Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "log" -> null
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "JvmLogFramework"
                else -> error("Unexpected framework call: ${method.name}")
            }
        })
    }

    @After
    fun restoreFramework() {
        frameworkField.set(HookRuntime, previousFramework)
    }

    @Test fun partialHookInstallationFailureUnlinksTheProvisionalListenerWithoutCallingAndroid() {
        val call = Call()
        try {
            assertEquals(false, RawGnssCallbacks.proceed(call.chain, install = {
                assertEquals(1, RawGnssCallbacks.subscriptions().size)
                error("Second callback hook could not be installed")
            }, callerUid = { 10001 }))
            assertEquals(0, call.proceeds)
            assertTrue("Failed hook setup leaked a raw subscription", RawGnssCallbacks.subscriptions().isEmpty())
            assertEquals(call.links, call.unlinks)
        } finally { RawGnssCallbacks.clear() }
    }

    @Test fun duplicateRegistrationFailurePreservesTheOriginalSubscription() {
        val call = Call()
        try {
            assertTrue(RawGnssCallbacks.proceed(call.chain, install = {}, callerUid = { 10001 }) as Boolean)
            val saved = RawGnssCallbacks.subscriptions().single()
            assertEquals(true, RawGnssCallbacks.proceed(call.chain,
                install = { error("Hook setup failed") }, callerUid = { 10002 }))
            assertEquals(saved, RawGnssCallbacks.subscriptions().single())
            assertEquals(1, call.proceeds)
            assertEquals(1, call.links)
            assertEquals(0, call.unlinks)
        } finally { RawGnssCallbacks.clear() }
        assertEquals(call.links, call.unlinks)
    }

    @Test fun platformRejectionAndFailureRollBackNewRegistrationsButNotExistingOnes() {
        for (existing in listOf(false, true)) for (throws in listOf(false, true)) {
            val call = Call()
            try {
                if (existing) RawGnssCallbacks.proceed(call.chain, install = {}, callerUid = { 10001 })
                val failure = IllegalArgumentException("Android rejected registration")
                call.failure = if (throws) failure else null
                call.result = false
                if (throws) assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
                    RawGnssCallbacks.proceed(call.chain, install = {}, callerUid = { 10001 })
                }) else assertEquals(false, RawGnssCallbacks.proceed(call.chain, install = {}, callerUid = { 10001 }))
                assertEquals(if (existing) 1 else 0, RawGnssCallbacks.subscriptions().size)
                assertEquals(if (existing) 2 else 1, call.proceeds)
            } finally { RawGnssCallbacks.clear() }
            assertEquals(call.links, call.unlinks)
        }
    }

    @Test fun restoreHookFailureDoesNotEscapeAndKeepsSubscriptionForInstalledGuards() {
        val call = Call()
        try {
            val subscription = CallbackSubscription(call.listener, 10001, "app")
            assertFalse(RawGnssCallbacks.restoreAndInstall(subscription) {
                error("An OEM callback method cannot be hooked")
            })
            assertEquals(1, RawGnssCallbacks.subscriptions().size)
            assertEquals(1, call.links)
            assertEquals(0, call.unlinks)
        } finally { RawGnssCallbacks.clear() }
        assertEquals(call.links, call.unlinks)
    }

    private class Call {
        var links = 0
        var unlinks = 0
        var proceeds = 0
        var result = true
        var failure: RuntimeException? = null
        private val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)) { proxy, method, args -> when (method.name) {
                "isBinderAlive" -> true
                "linkToDeath" -> { links++; null }
                "unlinkToDeath" -> { unlinks++; true }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            } } as IBinder
        val listener = object : IInterface { override fun asBinder(): IBinder = binder }
        val chain = Proxy.newProxyInstance(XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java)) { _, method, _ -> when (method.name) {
                "getExecutable" -> Service::class.java.getDeclaredMethod("addListener", IInterface::class.java)
                "getArgs" -> listOf(listener, "app")
                "proceed" -> { proceeds++; failure?.let { throw it }; result }
                else -> error(method.name)
            } } as XposedInterface.Chain
    }
    private class Service {
        @Suppress("UNUSED_PARAMETER") fun addListener(listener: IInterface): Boolean = true
    }
}
