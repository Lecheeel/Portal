package com.system.location.service.ui.home

import com.system.location.service.ui.viewmodel.AMapViewModel
import org.junit.Assert.*
import org.junit.Test

class OriginalLocationTest {
    @Test fun originalFixRejectsSimulationAndRemainsStableAsCurrentLocationMoves() {
        val model = AMapViewModel()
        val original = 39.9 to 116.4
        val simulated = 31.2 to 121.5
        assertFalse(model.rememberOriginalLocation(simulated, isMock = true, simulationActive = false))
        assertFalse(model.rememberOriginalLocation(simulated, isMock = false, simulationActive = true))
        assertFalse(model.rememberOriginalLocation(Double.NaN to 0.0, isMock = false, simulationActive = false))
        assertNull(model.originalLocation)
        assertTrue(model.rememberOriginalLocation(original, isMock = false, simulationActive = false))
        model.currentLocation = simulated
        model.markedLoc = 22.5 to 114.0
        assertFalse(model.rememberOriginalLocation(simulated, isMock = false, simulationActive = false))
        assertEquals(original, model.originalLocation)
        assertEquals(simulated, model.currentLocation)
    }
}
