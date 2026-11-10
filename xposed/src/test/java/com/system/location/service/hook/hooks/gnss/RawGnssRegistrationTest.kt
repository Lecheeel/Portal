package com.system.location.service.hook.hooks.gnss

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RawGnssRegistrationTest {
    private class Call(name: String, val failure: RuntimeException? = null) {
        var proceeds = 0
        val result = Any()
        val arguments = listOf(Any())
        private val executable = Service::class.java.getDeclaredMethod(name, Any::class.java)
        val chain = Proxy.newProxyInstance(XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java)) { _, method, _ -> when (method.name) {
                "getExecutable" -> executable
                "getArgs" -> arguments
                "proceed" -> { proceeds++; if (failure != null) throw failure; result }
                else -> error(method.name)
            } } as XposedInterface.Chain
    }
    @Suppress("UNUSED_PARAMETER")
    private class Service {
        fun addBoolean(listener: Any): Boolean = true
        fun addVoid(listener: Any) = Unit
        fun removeListener(listener: Any) = Unit
    }

    @Test fun rejectionIsFalseForBooleanApiAndExplicitFailureForVoidApi() {
        val hook = rawGnssRegistrationHook { true }
        val boolean = Call("addBoolean")
        assertEquals(false, hook.intercept(boolean.chain))
        assertEquals(0, boolean.proceeds)
        val void = Call("addVoid")
        assertThrows(UnsupportedOperationException::class.java) { hook.intercept(void.chain) }
        assertEquals(0, void.proceeds)
    }

    @Test fun policyPassthroughLeavesRealRegistrationIntact() {
        val call = Call("addBoolean")
        assertSame(call.result, rawGnssRegistrationHook { false }.intercept(call.chain))
        assertEquals(1, call.proceeds)
    }

    @Test fun policyReceivesTheCurrentCallArgumentsExactlyOnce() {
        val call = Call("addBoolean")
        var queries = 0
        val hook = rawGnssRegistrationHook { args ->
            queries++
            assertEquals(call.chain.args, args)
            true
        }
        assertEquals(false, hook.intercept(call.chain))
        assertEquals(1, queries)
        val excluded = rawGnssRegistrationHook { false }
        assertSame(call.result, excluded.intercept(call.chain))
    }

    @Test fun unregistrationAndOriginalFailureAlwaysReachAndroidExactlyOnce() {
        val call = Call("removeListener")
        val hook = rawGnssRegistrationHook { true }
        assertSame(call.result, hook.intercept(call.chain))
        assertEquals(1, call.proceeds)
        val noQuery = rawGnssRegistrationHook { error("removal queried policy") }
        assertSame(call.result, noQuery.intercept(call.chain))
        assertEquals(2, call.proceeds)
        val failure = IllegalStateException("Android registration failure")
        val failed = Call("addVoid", failure)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            rawGnssRegistrationHook { false }.intercept(failed.chain)
        })
        assertEquals(1, failed.proceeds)
    }
}
