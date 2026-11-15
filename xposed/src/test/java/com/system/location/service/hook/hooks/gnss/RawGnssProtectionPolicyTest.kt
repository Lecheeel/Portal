package com.system.location.service.hook.hooks.gnss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawGnssProtectionPolicyTest {
    @Test fun physicalRawPayloadIsSuppressedForProtectedSimulationEvenWithoutSyntheticFrames() {
        assertTrue(rawGnssPayloadProtectionApplies(simulationEnabled = true, protectedRoute = true))
    }

    @Test fun realPayloadRemainsAvailableOutsideSimulationOrForExplicitlyUnprotectedRoutes() {
        assertFalse(rawGnssPayloadProtectionApplies(simulationEnabled = false, protectedRoute = true))
        assertFalse(rawGnssPayloadProtectionApplies(simulationEnabled = true, protectedRoute = false))
    }
}
