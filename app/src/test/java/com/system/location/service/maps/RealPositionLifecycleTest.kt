package com.system.location.service.maps

import org.junit.Assert.*
import org.junit.Test

class RealPositionLifecycleTest {
    @Test fun simulationSuspendsFixesAndResumesOnlyWhileVisible() {
        val events = mutableListOf<Boolean>()
        val gate = RealPositionLifecycle { events += it }
        gate.resume(false)
        assertTrue(gate.acceptsFix())
        gate.simulation(true)
        assertFalse(gate.acceptsFix())
        gate.pause()
        gate.simulation(false)
        assertEquals(listOf(true, false), events)
        gate.resume(false)
        assertTrue(gate.acceptsFix())
        assertEquals(listOf(true, false, true), events)
    }
    @Test fun destroyingViewRejectsLateFixAndCannotRestart() {
        val events = mutableListOf<Boolean>()
        val gate = RealPositionLifecycle { events += it }
        gate.resume(false)
        gate.destroy()
        gate.resume(false)
        gate.simulation(false)
        gate.destroy()
        assertFalse(gate.acceptsFix())
        assertEquals(listOf(true, false), events)
    }
    @Test fun recreationDuringSimulationNeverStartsRealPositioning() {
        val events = mutableListOf<Boolean>()
        val old = RealPositionLifecycle { events += it }
        old.resume(false); old.simulation(true); old.destroy()
        val restored = RealPositionLifecycle { events += it }
        restored.resume(true)
        assertFalse(restored.acceptsFix())
        assertEquals(listOf(true, false), events)
        restored.simulation(false)
        restored.simulation(false)
        assertEquals(listOf(true, false, true), events)
    }
}
