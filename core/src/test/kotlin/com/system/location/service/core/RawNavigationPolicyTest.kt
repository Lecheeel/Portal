package com.system.location.service.core

import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssFrame
import com.system.location.service.core.gnss.GnssSatellite
import com.system.location.service.core.gnss.RawGnssMode
import com.system.location.service.core.gnss.RawNavigationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawNavigationPolicyTest {
    private fun satellite(svid: Int, constellation: Constellation, used: Boolean = true) = GnssSatellite(
        constellation = constellation,
        svid = svid,
        elevationDeg = 45f,
        azimuthDeg = 120f,
        cn0DbHz = 35f,
        usedInFix = used,
        hasEphemeris = true,
        hasAlmanac = true,
    )

    private val frame = GnssFrame(
        1_000,
        listOf(
            satellite(1, Constellation.GPS),
            satellite(2, Constellation.BEIDOU),
            satellite(3, Constellation.GALILEO, used = false),
        ),
        1f,
        2f,
        2.2f,
    )

    @Test fun offModeSuppressesEverything() {
        assertTrue(RawNavigationPolicy().messages(frame, 1_000L).isEmpty())
    }

    @Test fun experimentalModeGeneratesOnlyUsedSatellites() {
        val messages = RawNavigationPolicy(RawGnssMode.EXPERIMENTAL).messages(frame, 5_000L)
        assertEquals(2, messages.size)
        assertEquals(setOf(1, 2), messages.map { it.svid }.toSet())
        assertEquals(24, messages.first().data.size)
        assertEquals(5_000L, messages.first().receivedSvTimeNanos)
    }

    @Test fun constellationsMapToNavigationTypes() {
        val messages = RawNavigationPolicy(RawGnssMode.EXPERIMENTAL).messages(frame, 5_000L)
        assertEquals(com.system.location.service.core.gnss.RawNavigationType.GPS_LNAV, messages[0].type)
        assertEquals(com.system.location.service.core.gnss.RawNavigationType.BDS_D1, messages[1].type)
    }

    @Test fun outputIsDeterministicAndBounded() {
        val policy = RawNavigationPolicy(RawGnssMode.EXPERIMENTAL)
        assertEquals(policy.messages(frame, 5_000L), policy.messages(frame, 5_000L))
        assertEquals(1, RawNavigationPolicy(RawGnssMode.EXPERIMENTAL, maxMessages = 1).messages(frame, 5_000L).size)
    }

    @Test fun invalidConfigurationAndTimeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RawNavigationPolicy(maxMessages = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            RawNavigationPolicy(RawGnssMode.EXPERIMENTAL).messages(frame, -1L)
        }
    }
}
