package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GlonassBroadcastStateVectorTest {
    private val zero = GlonassCartesianVector(0.0, 0.0, 0.0)
    private fun state() = GlonassBroadcastStateVector(1, GlonassReferenceFrame.PZ_90_02,
        GlonassCartesianVector(8_450_493.652344, -18_744_716.79688, 15_109_795.41016),
        GlonassCartesianVector(1381.167411804, -1543.292045593, -2684.219360352), zero)

    @Test fun frameAndSlotAreExplicitEvidenceWithoutSatelliteCountHeuristics() {
        val original = state()
        assertEquals(31, original.copy(slot = 31).slot)
        assertEquals(GlonassReferenceFrame.PZ_90_11,
            original.copy(referenceFrame = GlonassReferenceFrame.PZ_90_11).referenceFrame)
        assertThrows(IllegalArgumentException::class.java) { original.copy(slot = 0) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(slot = 32) }
    }

    @Test fun rejectsNonfiniteUnrepresentableAndSingularInitialConditions() {
        val s = state()
        assertThrows(IllegalArgumentException::class.java) { GlonassCartesianVector(Double.NaN, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GlonassCartesianVector(0.0, Double.POSITIVE_INFINITY, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { s.copy(positionMeters = zero) }
        assertThrows(IllegalArgumentException::class.java) {
            s.copy(positionMeters = GlonassCartesianVector(32_768_000.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.copy(velocityMetersPerSecond = GlonassCartesianVector(8000.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.copy(luniSolarAccelerationMetersPerSecondSquared = GlonassCartesianVector(1.4e-5, 0.0, 0.0))
        }
    }
}
