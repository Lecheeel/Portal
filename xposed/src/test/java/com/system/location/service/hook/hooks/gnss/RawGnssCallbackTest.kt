package com.system.location.service.hook.hooks.gnss

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RawGnssCallbackTest {
    @Suppress("UNUSED_PARAMETER")
    private class Callback {
        fun onGnssMeasurementsReceived(event: Any) = Unit
        fun onGnssNavigationMessageReceived(event: Any) = Unit
        fun onGnssAntennaInfoReceived(event: Any) = Unit
        fun onStatusChanged(status: Int) = Unit
    }
    private class Call(name: String) {
        var proceeds = 0
        var arguments: List<Any?> = listOf(if (name == "onStatusChanged") 1 else Any())
        private val executable = Callback::class.java.declaredMethods.single { it.name == name }
        val result = Any()
        val chain = Proxy.newProxyInstance(XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java)) { _, method, args -> when (method.name) {
                "getExecutable" -> executable
                "getArgs" -> arguments
                "getThisObject" -> null
                "proceed" -> {
                    proceeds++
                    if (!args.isNullOrEmpty()) arguments = (args.single() as Array<*>).toList()
                    result
                }
                else -> error(method.name)
            } } as XposedInterface.Chain
    }

    @Test fun existingRawListenerFollowsLivePolicyAndCanResume() {
        var blocked = false
        val hook = rawGnssCallbackHook { blocked }
        for (name in listOf("onGnssMeasurementsReceived", "onGnssNavigationMessageReceived", "onGnssAntennaInfoReceived")) {
            val real = Call(name)
            assertSame(real.result, hook.intercept(real.chain))
            blocked = true
            val suppressed = Call(name)
            assertNull(hook.intercept(suppressed.chain))
            assertEquals(0, suppressed.proceeds)
            blocked = false
            val resumed = Call(name)
            assertSame(resumed.result, hook.intercept(resumed.chain))
            assertEquals(1, resumed.proceeds)
        }
    }

    @Test fun blockedRawCallbackReportsUnsupportedRatherThanReady() {
        val call = Call("onStatusChanged")
        rawGnssCallbackHook { true }.intercept(call.chain)
        assertEquals(1, call.proceeds)
        assertEquals(listOf(0), call.arguments)
    }
}
