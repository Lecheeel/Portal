package com.system.location.service.gnss

import org.junit.Assert.*
import org.junit.Test

class SatelliteStartupDefaultsTest {
    @Test fun rootedAndActiveSystemModuleDefaultsToTargetSky() {
        assertEquals(mapOf("gnssReplayOnStart" to true, "gnssSimulationMode" to "AUTONOMOUS"),
            SatelliteStartupDefaults.additions(emptyMap<String, Any>(), true, true))
    }

    @Test fun missingRootOrAnInactiveModuleDoesNotChangeChoices() {
        for ((root, module) in listOf(false to false, false to true, true to false)) {
            assertTrue(SatelliteStartupDefaults.additions(emptyMap<String, Any>(), root, module).isEmpty())
        }
    }

    @Test fun explicitReplayAndAutomaticPoliciesKeepTheirSources() {
        for (mode in listOf("REPLAY", "AUTO", "AUTONOMOUS")) {
            assertEquals(mapOf("gnssReplayOnStart" to true),
                SatelliteStartupDefaults.additions(mapOf("gnssSimulationMode" to mode), true, true))
        }
    }

    @Test fun explicitDisableAndExistingStartupChoicesArePreserved() {
        for (preferences in listOf(mapOf("gnssSimulationMode" to "OFF"),
            mapOf("gnssReplayOnStart" to false, "gnssSimulationMode" to "AUTONOMOUS"),
            mapOf("gnssReplayOnStart" to true, "gnssSimulationMode" to "REPLAY"))) {
            assertTrue(SatelliteStartupDefaults.additions(preferences, true, true).isEmpty())
        }
    }

    @Test fun applyingDefaultsAgainDoesNotOverwriteALaterChoice() {
        val initial = SatelliteStartupDefaults.additions(emptyMap<String, Any>(), true, true)
        assertTrue(SatelliteStartupDefaults.additions(initial, true, true).isEmpty())
        assertTrue(SatelliteStartupDefaults.additions(initial + ("gnssSimulationMode" to "REPLAY"), true, true).isEmpty())
    }
}
