package com.system.location.service.hook.hooks.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorProtectionPolicyTest {
    @Test
    fun revokedTargetClearsTheProtectionGrant() {
        val decision = SensorProtectionPolicy.evaluate(false, true, false, true, true)

        assertTrue(decision.handled)
        assertFalse(decision.protected)
    }

    @Test
    fun missingMotionSampleKeepsTheProtectedState() {
        val decision = SensorProtectionPolicy.evaluate(true, true, false, true, true)

        assertTrue(decision.handled)
        assertTrue(decision.protected)
    }

    @Test
    fun stoppedAndDeniedRunsDoNotUseJavaProtection() {
        assertFalse(SensorProtectionPolicy.evaluate(true, false, false, true, true).protected)
        assertFalse(SensorProtectionPolicy.evaluate(true, true, false, false, true).protected)
    }

    @Test
    fun stepAndMotionChannelsCanBeOwnedIndependently() {
        val nativeMotion = SensorProtectionPolicy.evaluate(true, true, true, true, true)
        assertFalse(nativeMotion.motion)
        assertTrue(nativeMotion.steps)
        val javaMotion = SensorProtectionPolicy.evaluate(true, true, false, true, false)
        assertTrue(javaMotion.motion)
        assertFalse(javaMotion.steps)
        assertFalse(SensorProtectionPolicy.evaluate(true, true, true, true, false).protected)
    }
}
