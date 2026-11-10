package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoCaptureEphemeridesTest {
    private val gst = GalileoTime(1081, 432700.0)
    private val offset = GalileoGpsTimeOffset(1081, 120, 1000, 20,
        gst.plusSeconds(-1000.0), gst.plusSeconds(1000.0))
    private fun capture(): GnssCapture {
        val pages = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-pages-reference.json")!!.readText())
            .jsonObject.getValue("pages").jsonArray.map { it.jsonObject }.filter {
                val type = it.getValue("type").jsonPrimitive.int
                type == 0x0602 || type == 0x0601 && it.getValue("wordType").jsonPrimitive.int in 1..5
            }
        val navigation = pages.mapIndexed { index, page ->
            val bytes = page.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            GnssNavigationObservation(1000L + index * 1000, page.getValue("type").jsonPrimitive.int, 24, 1, -1,
                page.getValue("wordType").jsonPrimitive.int, Base64.getEncoder().encodeToString(bytes),
                100_000_000_000L + index * 1_000_000_000L)
        }
        val measurements = navigation.mapIndexed { index, nav ->
            val gps = offset.galileoToGps(gst.plusSeconds(index.toDouble())).time!!
            // This synthetic clock is sub-nanosecond rounded; full integer subtraction remains exact.
            val nanos = gps.week * 604800_000_000_000L + (gps.secondsOfWeek * 1e9).toLong()
            GnssMeasurementBatch(nav.capturedAtMillis, 0, -nanos, 0.0, 0, emptyList(),
                timeUncertaintyNanos = 1.0, biasUncertaintyNanos = 1.0,
                elapsedRealtimeNanos = nav.capturedElapsedRealtimeNanos,
                elapsedRealtimeUncertaintyNanos = 1.0, capturedElapsedRealtimeNanos = nav.capturedElapsedRealtimeNanos)
        }
        return GnssCapture(deviceModel = "synthetic", osVersion = "test", startedAtMillis = 0, endedAtMillis = 60000,
            frames = listOf(GnssObservationFrame(0, satellites = emptyList())), measurements = measurements, navigation = navigation)
    }

    @Test fun decodesBothFormatsFromAssociatedReceiverClocksAndApplicableGgto() {
        val c = capture()
        val result = GalileoCaptureEphemerisDecoder.decode(c, offset)
        assertEquals(1, result.inav.size)
        assertEquals(1, result.fnav.size)
        assertEquals(GalileoTime(1081, 432000.0), result.inav.single().ephemeris.toe)
        assertEquals(GalileoTime(1081, 446400.0), result.fnav.single().ephemeris.validUntil)
        val changed = c.copy(navigation = c.navigation.reversed().map { it.copy(capturedAtMillis = it.capturedAtMillis + 1000) })
        val other = GalileoCaptureEphemerisDecoder.decode(changed, offset)
        assertEquals(result.inav, other.inav)
        assertEquals(result.fnav, other.fnav)
    }

    @Test fun missingOrExpiredGgtoAndUnknownClockTimingNeverBecomeZeroOffsetFallbacks() {
        val c = capture()
        fun assertEmpty(value: GalileoCaptureEphemerides) {
            assertTrue(value.inav.isEmpty()); assertTrue(value.fnav.isEmpty()); assertTrue(value.diagnostics.isNotEmpty())
        }
        assertEmpty(GalileoCaptureEphemerisDecoder.decode(c, null))
        assertEmpty(GalileoCaptureEphemerisDecoder.decode(c, offset.copy(
            validFrom = gst.plusSeconds(-2000.0), validUntil = gst.plusSeconds(-1000.0))))
        assertEmpty(GalileoCaptureEphemerisDecoder.decode(c.copy(measurements = emptyList()), offset))
        assertEmpty(GalileoCaptureEphemerisDecoder.decode(c.copy(measurements = c.measurements.map {
            it.copy(elapsedRealtimeUncertaintyNanos = null)
        }), offset))
    }

    @Test fun unassociatedNewServiceEvidenceWithdrawsOldDataOfThatFormat() {
        val c = capture()
        val newer = c.navigation.first { it.type == 0x0601 && it.submessageId == 5 }
            .copy(capturedElapsedRealtimeNanos = null, capturedAtMillis = 60000)
        val result = GalileoCaptureEphemerisDecoder.decode(c.copy(navigation = c.navigation + newer), offset)
        assertTrue(result.inav.isEmpty())
        assertEquals(1, result.fnav.size)
    }

    @Test fun clockResetAndInconsistentAnchorsCannotJoinACompleteEphemeris() {
        val c = capture()
        for (measurements in listOf(c.measurements.mapIndexed { i, b -> b.copy(hardwareClockDiscontinuityCount = i % 2) },
            c.measurements.mapIndexed { i, b -> if (i == 1) b.copy(fullBiasNanos = b.fullBiasNanos!! - 5_000_000_000L) else b })) {
            val result = GalileoCaptureEphemerisDecoder.decode(c.copy(measurements = measurements), offset)
            assertTrue(result.inav.isEmpty())
            assertTrue(result.fnav.isEmpty())
        }
    }
}
