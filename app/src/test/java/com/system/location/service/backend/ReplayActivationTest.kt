package com.system.location.service.backend

import com.system.location.service.backend.xposed.ReplayActivation
import org.junit.Assert.*
import org.junit.Test

class ReplayActivationTest {
    @Test fun clearsPreviousSwitchesWhenDisabled() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        assertTrue(ReplayActivation.apply(false, false,
            { calls += "environment" to it; true }, { calls += "satellite" to it; true }))
        assertEquals(listOf("environment" to false, "satellite" to false), calls)
    }

    @Test fun enablesBothSelectedSources() {
        val calls = mutableListOf<Boolean>()
        assertTrue(ReplayActivation.apply(true, true, { calls += it; true }, { calls += it; true }))
        assertEquals(listOf(true, true), calls)
    }

    @Test fun activationFailureIsNotReportedAsSuccess() {
        var satelliteCalled = false
        assertFalse(ReplayActivation.apply(true, true, { false }, { satelliteCalled = true; true }))
        assertFalse(satelliteCalled)
        assertFalse(ReplayActivation.apply(true, true, { true }, { false }))
    }
}
