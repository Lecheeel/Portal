package com.system.location.service.core

import com.system.location.service.core.runtime.*
import com.system.location.service.core.sensors.SensorTargets
import org.junit.Assert.*
import org.junit.Test

class SensorTargetPolicyTest {
    @Test fun onlyExactApplicationUidsWithEntireSharedUidOptedInMayRead() {
        val targets = setOf("com.example.target")
        assertTrue(SensorTargets.allows(10123, targets, targets))
        assertFalse(SensorTargets.allows(1000, targets, targets))
        assertFalse(SensorTargets.allows(99123, targets, targets))
        assertFalse(SensorTargets.allows(10123, emptySet(), targets))
        assertFalse(SensorTargets.allows(10123, targets + "com.example.other", targets))
        assertFalse(SensorTargets.allows(10123, setOf("com.example.target.extra"), targets))
    }
    @Test fun updatesAreAtomicAndEmptyListDisablesInjection() {
        val old = RuntimeSnapshot(configuration = HookConfiguration(sensorTargets = setOf("com.example.target")))
        val cleared = PreparedRuntimeConfiguration.prepare(mapOf("sensor_targets" to "")).applyTo(old)
        assertTrue(cleared.configuration.sensorTargets.isEmpty())
        assertEquals(old.configuration.sensorTargets, PreparedRuntimeConfiguration.prepare(emptyMap()).applyTo(old).configuration.sensorTargets)
        try { PreparedRuntimeConfiguration.prepare(mapOf("sensor_targets" to "../bad")); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(setOf("com.example.a", "com.example.b"), SensorTargets.parse("com.example.a, com.example.b"))
    }
}
