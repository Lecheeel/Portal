package com.system.location.service.core

import com.system.location.service.core.gnss.AntennaProfile
import com.system.location.service.core.gnss.RawAntennaMode
import com.system.location.service.core.gnss.RawAntennaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RawAntennaPolicyTest {
    @Test fun offReturnsNull() {
        assertNull(RawAntennaPolicy(RawAntennaMode.OFF).profile())
    }

    @Test fun neutralReturnsZeros() {
        val profile = RawAntennaPolicy(RawAntennaMode.DEVICE_NEUTRAL).profile()!!
        assertEquals(listOf(0.0, 0.0, 0.0), profile.phaseCenterOffsetMm)
        assertEquals(0.0, profile.signalGainDb, 0.0)
    }

    @Test fun experimentalIsDeterministic() {
        val expected = AntennaProfile(
            phaseCenterOffsetMm = listOf(1.0, 2.0, 3.0),
            phaseCenterVariationMm = listOf(0.1, 0.2, 0.3),
            signalGainDb = 1.5,
            gainVariationDb = 0.5,
        )
        val policy = RawAntennaPolicy(RawAntennaMode.EXPERIMENTAL, expected)
        assertEquals(expected, policy.profile())
        assertEquals(expected, policy.profile())
    }

    @Test fun invalidProfilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AntennaProfile(phaseCenterOffsetMm = listOf(0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AntennaProfile(phaseCenterVariationMm = listOf(-1.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AntennaProfile(gainVariationDb = -0.1)
        }
    }
}
