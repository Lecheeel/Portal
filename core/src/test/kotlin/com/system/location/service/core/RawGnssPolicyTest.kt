package com.system.location.service.core

import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssFrame
import com.system.location.service.core.gnss.GnssSatellite
import com.system.location.service.core.gnss.RawGnssMode
import com.system.location.service.core.gnss.RawGnssPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawGnssPolicyTest {
    private fun satellite(svid: Int, cn0: Float = 35f, used: Boolean = true) = GnssSatellite(
        constellation = Constellation.GPS,
        svid = svid,
        elevationDeg = 45f,
        azimuthDeg = 120f,
        cn0DbHz = cn0,
        usedInFix = used,
        hasEphemeris = true,
        hasAlmanac = true,
    )

    private val frame = GnssFrame(1_000, listOf(satellite(1), satellite(2, cn0 = 10f), satellite(3, used = false)), 1f, 2f, 2.2f)

    @Test fun offModeSuppressesEverything() {
        assertTrue(RawGnssPolicy().measurements(frame, 1_000L).isEmpty())
    }

    @Test fun experimentalModeOnlyUsesFixAndCn0Threshold() {
        val measurements = RawGnssPolicy(RawGnssMode.EXPERIMENTAL).measurements(frame, 1_000L)
        assertEquals(1, measurements.size)
        assertEquals(1, measurements.single().svid)
        assertEquals(Constellation.GPS, measurements.single().constellation)
        assertEquals(1_000L, measurements.single().timeNanos)
    }

    @Test fun measurementsAreDeterministic() {
        val policy = RawGnssPolicy(RawGnssMode.EXPERIMENTAL, minCn0DbHz = 0f)
        val first = policy.measurements(frame, 5_000L)
        val second = policy.measurements(frame, 5_000L)
        assertEquals(first, second)
        assertEquals(2, first.size)
    }

    @Test fun measurementLimitIsRespected() {
        val full = GnssFrame(1_000, (1..20).map { satellite(it) }, 1f, 2f, 2.2f)
        assertEquals(3, RawGnssPolicy(RawGnssMode.EXPERIMENTAL, maxMeasurements = 3).measurements(full, 1L).size)
    }

    @Test fun invalidConfigurationAndTimeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RawGnssPolicy(minCn0DbHz = -1f) }
        assertThrows(IllegalArgumentException::class.java) { RawGnssPolicy(maxMeasurements = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            RawGnssPolicy(RawGnssMode.EXPERIMENTAL).measurements(frame, -1L)
        }
    }
}
