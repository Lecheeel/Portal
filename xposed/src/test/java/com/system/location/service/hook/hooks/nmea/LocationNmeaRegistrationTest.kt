package com.system.location.service.hook.hooks.nmea

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationNmeaRegistrationTest {
    @Test
    fun existingListenersCanAlwaysBeRemovedWhileNmeaIsDisabled() {
        val hook = nmeaRegistrationHook(enable = { true }, enableNmea = { false })
        for (method in REMOVE_METHODS) {
            val call = OriginalCall(method)
            assertSame(call.result, hook.intercept(call.chain))
            assertEquals("$method must unregister exactly once", 1, call.proceedCount)
            assertSame(call.listener, call.deliveredArgs!!.single())
        }
    }

    @Test
    fun newListenersAreSuppressedWhileNmeaIsDisabled() {
        val hook = nmeaRegistrationHook(enable = { true }, enableNmea = { false })
        for (method in ADD_METHODS) {
            val call = OriginalCall(method)
            assertNull(hook.intercept(call.chain))
            assertEquals("$method must not register", 0, call.proceedCount)
        }
    }

    @Test
    fun liveSwitchChangesRestoreNormalRegistration() {
        var active = true
        var nmea = false
        val hook = nmeaRegistrationHook(enable = { active }, enableNmea = { nmea })
        for (method in ADD_METHODS) {
            active = false
            nmea = false
            val inactive = OriginalCall(method)
            assertSame(inactive.result, hook.intercept(inactive.chain))
            assertEquals(1, inactive.proceedCount)
            assertSame(inactive.listener, inactive.deliveredArgs!!.single())

            active = true
            nmea = true
            val enabled = OriginalCall(method)
            assertSame(enabled.result, hook.intercept(enabled.chain))
            assertEquals(1, enabled.proceedCount)

            nmea = false
            val disabled = OriginalCall(method)
            assertNull(hook.intercept(disabled.chain))
            assertEquals(0, disabled.proceedCount)
        }
    }

    @Test
    fun originalUnregistrationFailurePropagatesWithoutRetry() {
        val hook = nmeaRegistrationHook(enable = { true }, enableNmea = { false })
        for (method in REMOVE_METHODS) {
            val failure = IllegalStateException("unregistration failed")
            val call = OriginalCall(method, failure)
            assertSame(
                failure,
                assertThrows(IllegalStateException::class.java) { hook.intercept(call.chain) },
            )
            assertEquals(1, call.proceedCount)
        }
    }

    @Test
    fun originalRegistrationFailurePropagatesWithoutRetry() {
        val hook = nmeaRegistrationHook(enable = { false }, enableNmea = { false })
        val failure = IllegalArgumentException("invalid listener")
        val call = OriginalCall(ADD_METHODS.first(), failure)
        assertSame(
            failure,
            assertThrows(IllegalArgumentException::class.java) { hook.intercept(call.chain) },
        )
        assertEquals(1, call.proceedCount)
    }

    private class OriginalCall(name: String, failure: RuntimeException? = null) {
        val listener = Any()
        val result = Any()
        var proceedCount = 0
        var deliveredArgs: List<Any?>? = null
        private val args = listOf(listener)
        private val executable =
            ListenerService::class.java.getDeclaredMethod(name, Any::class.java)
        val chain =
            Proxy.newProxyInstance(
                XposedInterface.Chain::class.java.classLoader,
                arrayOf(XposedInterface.Chain::class.java),
            ) { _, method, invocationArgs ->
                when (method.name) {
                    "getExecutable" -> executable
                    "getArgs" -> args
                    "proceed" -> {
                        proceedCount++
                        deliveredArgs =
                            if (invocationArgs.isNullOrEmpty()) args
                            else (invocationArgs.single() as Array<*>).toList()
                        if (failure != null) throw failure
                        result
                    }
                    else -> error("Unexpected chain operation: ${method.name}")
                }
            } as XposedInterface.Chain
    }

    @Suppress("UNUSED_PARAMETER")
    private class ListenerService {
        fun addGnssMeasurementsListener(listener: Any) = Unit

        fun removeGnssMeasurementsListener(listener: Any) = Unit

        fun addGnssNavigationMessageListener(listener: Any) = Unit

        fun removeGnssNavigationMessageListener(listener: Any) = Unit

        fun addGnssAntennaInfoListener(listener: Any) = Unit

        fun removeGnssAntennaInfoListener(listener: Any) = Unit
    }

    companion object {
        private val ADD_METHODS =
            listOf(
                "addGnssMeasurementsListener",
                "addGnssNavigationMessageListener",
                "addGnssAntennaInfoListener",
            )
        private val REMOVE_METHODS =
            listOf(
                "removeGnssMeasurementsListener",
                "removeGnssNavigationMessageListener",
                "removeGnssAntennaInfoListener",
            )
    }
}
