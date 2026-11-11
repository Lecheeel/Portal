package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GnssUtcTimeScaleTest {
    private fun millis(utc: String) = Instant.parse(utc).toEpochMilli()
    private val text = requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText()
    private val scale = GnssUtcTimeScale.parseIers(text)

    @Test fun gpsEpochAndKnownModernEpochUseDifferentOffsets() {
        assertEquals(GpsTime(0, 0.0), scale.gpsAtUtcMillis(millis("1980-01-06T00:00:00Z")).time)
        // 2017-01-01 starts GPS week 1930, with GPS-UTC = 18 seconds.
        assertEquals(GpsTime(1930, 18.0), scale.gpsAtUtcMillis(millis("2017-01-01T00:00:00Z")).time)
        assertEquals(GpsTime(1930, 18.123), scale.gpsAtUtcMillis(millis("2017-01-01T00:00:00.123Z")).time)
    }

    @Test fun leapBoundaryAddsTwoGpsSecondsBetweenAdjacentRepresentableUtcSeconds() {
        val before = scale.gpsAtUtcMillis(millis("2016-12-31T23:59:59Z")).time!!
        val after = scale.gpsAtUtcMillis(millis("2017-01-01T00:00:00Z")).time!!
        assertEquals(2.0, after.secondsSince(before), 0.0)
        val historical = scale.gpsAtUtcMillis(millis("2015-07-01T00:00:00Z")).time!!
        assertEquals(17.0, historical.secondsOfWeek % 86400, 0.0)
    }

    @Test fun refusesUnknownFutureAndPreGpsEpochInsteadOfFreezingOffset() {
        assertNull(scale.gpsAtUtcMillis(GnssUtcTimeScale.GPS_EPOCH_MILLIS - 1).time)
        assertNotNull(scale.gpsAtUtcMillis(scale.expiresAtMillis - 1).time)
        assertNull(scale.gpsAtUtcMillis(scale.expiresAtMillis).time)
        assertNull(scale.gpsAtUtcMillis(Long.MAX_VALUE).time)
    }

    @Test fun rejectsBrokenResourcesAndInconsistentOffsetSequence() {
        for (invalid in listOf("", text.replace("#@", "#ignored"), "$text\n#@ 4023129600",
            text.replace("3692217600      37", "3692217600      40"),
            "$text\n3692217600 37", "x".repeat(65537))) {
            assertThrows(IllegalArgumentException::class.java) { GnssUtcTimeScale.parseIers(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GnssUtcTimeScale(listOf(GnssUtcOffset(0, 18)), Long.MAX_VALUE)
        }
    }

    @Test fun constructorCopiesCallerOwnedTransitions() {
        val input = mutableListOf(GnssUtcOffset(0, 19))
        val immutable = GnssUtcTimeScale(input, millis("1981-01-01T00:00:00Z"))
        input.clear()
        assertEquals(GpsTime(0, 0.0), immutable.gpsAtUtcMillis(GnssUtcTimeScale.GPS_EPOCH_MILLIS).time)
    }
}
