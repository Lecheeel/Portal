package com.system.location.service.ui.gnss

import com.system.location.service.android.widget.SatelliteData
import org.junit.Assert.*
import org.junit.Test

class GnssSatelliteDisplayTest {
    private fun signal(system: Int, id: Int, cn0: Float = 35f, used: Boolean = false) =
        SatelliteData(id, cn0, 45f, 120f, true, true, used, constellationType = system)

    @Test fun multibandSpacecraftCountsOnceWhileEqualSvidsAcrossSystemsRemainDistinct() {
        val used = signal(1, 1, 30f, true)
        val signals = listOf(signal(1, 1), used, signal(6, 1), signal(5, 2))
        val displayed = displaySpacecraft(signals)
        assertEquals(listOf(used, signals[3], signals[2]), displayed)
        assertEquals(displayed, displaySpacecraft(signals.reversed()))
        assertEquals(1, displayed.count { it.usedInFix })
    }

    @Test fun invalidAndZeroSignalBandsDoNotHideValidSpacecraft() {
        val good = signal(1, 1)
        val invalid = listOf(signal(1, 1, 0f, true), signal(1, 1, 64f),
            signal(1, 1, Float.NaN), good.copy(elevation = -1f),
            good.copy(azimuth = 360f), good.copy(elevation = Float.POSITIVE_INFINITY))
        assertEquals(listOf(good), displaySpacecraft(invalid + good))
        assertTrue(displaySpacecraft(invalid).isEmpty())
    }
}
