package com.system.location.service.core

import com.system.location.service.core.sensors.*
import org.junit.Assert.*
import org.junit.Test

class SensorPlaybackTest {
    private fun grant(session: String = "one", sample: Long = 1_000_000_000) =
        SensorPlaybackState(session, sample, sample + 2_000_000_000, 1.5f, 0f, 0.0)

    @Test fun expiryAndRevocationRestorePassThrough() {
        val playback = SensorPlayback()
        playback.update(grant(), 1_000_000_000)
        assertNotNull(playback.frame(1_500_000_000))
        assertNull(playback.frame(3_000_000_000))
        playback.update(null, 1_500_000_000)
        assertNull(playback.frame(1_500_000_000))
    }

    @Test fun listenersShareStepsAndNewSessionsResetThem() {
        val playback = SensorPlayback()
        playback.update(grant(), 1_000_000_000)
        playback.frame(1_000_000_000)
        val later = playback.frame(2_500_000_000)!!
        assertTrue(later.stepCount > 0)
        assertSame(later, playback.frame(2_500_000_000))
        playback.update(grant("two", 2_500_000_000), 2_500_000_000)
        assertEquals(0L, playback.frame(2_500_000_000)!!.stepCount)
    }
}
