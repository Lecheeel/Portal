package com.system.location.service.hook.hooks.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorProtectionPolicyTest {
    @Test
    fun revokedTargetClearsTheProtectionGrant() {
        val decision = SensorProtectionPolicy.evaluate(false, true, false, true)

        assertTrue(decision.handled)
        assertFalse(decision.protected)
    }

    @Test
    fun missingMotionSampleKeepsTheProtectedState() {
        val decision = SensorProtectionPolicy.evaluate(true, true, false, true)

        assertTrue(decision.handled)
        assertTrue(decision.protected)
    }

    @Test
    fun stoppedDeniedAndNativeRunsDoNotUseJavaProtection() {
        assertFalse(SensorProtectionPolicy.evaluate(true, false, false, true).protected)
        assertFalse(SensorProtectionPolicy.evaluate(true, true, false, false).protected)
        assertFalse(SensorProtectionPolicy.evaluate(true, true, true, true).protected)
    }
}
