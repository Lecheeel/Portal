package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalileoBroadcastEvidenceTest {
    @Test fun missingIndependentGgtoDoesNotCreateEvidence() {
        val capture = GnssCapture(
            deviceModel = "receiver", osVersion = "os", startedAtMillis = 0, endedAtMillis = 60_000,
            frames = (0..60).map { index -> GnssObservationFrame(index * 1000L,
                satellites = listOf(GnssSatellite(Constellation.GALILEO, 1, 30f, 90f, 35f, true, true, true))) },
        )
        assertNull(GalileoBroadcastEvidence.fromCapture(capture, null))
    }

    @Test fun wireRoundTripPreservesExplicitEvidence() {
        val time = GalileoTime(100, 10_000.0)
        val offset = GalileoGpsTimeOffset(10, 2, 0, 0, time, time.plusSeconds(3_600.0))
        val clock = GpsElapsedClockEvidence(7, 1034, 10_000.0, 5_000_000_000L, 1e-6, 1e-6)
        val eph = GalileoBroadcastEphemeris(1, time, time, time, time.plusSeconds(3_600.0), 1,
            GalileoClockReference.INAV_E1_E5B, 5_440.0, 0.01, 0.94, 1.0, 0.5, 0.2,
            0.0, -1e-9, 1e-10, 0.0, 0.0, 10.0, 20.0, 0.0, 0.0, 1e-4, 0.0, 0.0)
        val decoded = DecodedGalileoInav(eph, time, GalileoSignalStatus(0, 0),
            GalileoSignalStatus(0, 0), 10, 0.0, 0.0)
        val fnav = DecodedGalileoFnav(eph.copy(clockReference = GalileoClockReference.FNAV_E1_E5A), time,
            GalileoSignalStatus(0, 0), 10, 0.0)
        val capture = GnssCapture(deviceModel = "receiver", osVersion = "os", startedAtMillis = 0,
            endedAtMillis = 60_000, bootCount = 7,
            frames = (0..60).map { index -> GnssObservationFrame(index * 1000L,
                satellites = listOf(GnssSatellite(Constellation.GALILEO, 1, 30f, 90f, 35f, true, true, true))) },
            galileoBroadcastEvidence = GalileoBroadcastEvidence(clock, offset, GalileoReceiverFrame.GTRF,
                inav = listOf(decoded), fnav = listOf(fnav)))
        val result = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
        assertEquals(capture.galileoBroadcastEvidence, result.galileoBroadcastEvidence)
        assertEquals(7, ReceiverBroadcastSkyModel(result).broadcastBootCount)
    }
}
