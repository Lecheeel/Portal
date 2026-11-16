package com.system.location.service.core

import com.system.location.service.core.carrier.CarrierCellPublicationGate
import org.junit.Assert.*
import org.junit.Test

class CarrierCellPublicationGateTest {
    @Test fun stationarySamplesAreSuppressedAndLatestTileEventuallyPublishes() {
        val gate = CarrierCellPublicationGate(1500)
        assertTrue(gate.changed(-33.86, 151.20, 0))
        assertFalse(gate.changed(-33.86, 151.20, 2000))
        assertFalse(gate.changed(-33.0, 150.0, 100))
        assertFalse(gate.changed(-32.0, 149.0, 1499))
        assertTrue(gate.changed(-32.0, 149.0, 1500))
        assertFalse(gate.changed(-32.0, 149.0, 4000))
        assertTrue(gate.changed(-31.0, 149.0, 4000))
    }
    @Test fun rejectsInvalidInputWithoutPoisoningNextValidPublication() {
        val gate = CarrierCellPublicationGate()
        assertFalse(gate.changed(Double.NaN, 0.0, 0))
        assertFalse(gate.changed(0.0, 0.0, -1))
        assertTrue(gate.changed(0.0, 0.0, 0))
    }
}
