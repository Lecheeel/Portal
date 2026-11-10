package com.system.location.service.hook.hooks.gnss

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RawGnssRegistrationTest {
    private class Call(name: String, val failure: RuntimeException? = null) {
        var proceeds = 0
        val result = Any()
        private val executable = Service::class.java.getDeclaredMethod(name, Any::class.java)
        val chain = Proxy.newProxyInstance(XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java)) { _, method, _ -> when (method.name) {
                "getExecutable" -> executable
                "getArgs" -> listOf(Any())
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
        val hook = rawGnssRegistrationHook({ true }, { false }, { true })
        val boolean = Call("addBoolean")
        assertEquals(false, hook.intercept(boolean.chain))
        assertEquals(0, boolean.proceeds)
        val void = Call("addVoid")
        assertThrows(UnsupportedOperationException::class.java) { hook.intercept(void.chain) }
        assertEquals(0, void.proceeds)
    }

    @Test fun inactiveMissingCaptureOrPhysicalOptInLeavesRealRegistrationIntact() {
        for (flags in listOf(Triple(false, false, true), Triple(true, true, true), Triple(true, false, false))) {
            val call = Call("addBoolean")
            val hook = rawGnssRegistrationHook({ flags.first }, { flags.second }, { flags.third })
            assertSame(call.result, hook.intercept(call.chain))
            assertEquals(1, call.proceeds)
        }
    }

    @Test fun unregistrationAndOriginalFailureAlwaysReachAndroidExactlyOnce() {
        val call = Call("removeListener")
        val hook = rawGnssRegistrationHook({ true }, { false }, { true })
        assertSame(call.result, hook.intercept(call.chain))
        assertEquals(1, call.proceeds)
        val failure = IllegalStateException("Android registration failure")
        val failed = Call("addVoid", failure)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            rawGnssRegistrationHook({ false }, { false }, { true }).intercept(failed.chain)
        })
        assertEquals(1, failed.proceeds)
    }
}
