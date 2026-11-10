package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BeiDouBroadcastEvidenceTest {
    @Test fun explicitBdtEvidenceSurvivesWireRoundTrip() {
        val time = BeiDouTime(700, 100_000.0)
        val validUntil = time.plusSeconds(3_600.0)
        val e = BeiDouBroadcastEphemeris(1, BeiDouOrbitType.MEO, time, time, time, validUntil,
            5_440.0, 0.01, 0.94, 1.0, 0.5, 0.2, 0.0, -1e-9, 1e-10,
            0.0, 0.0, 10.0, 20.0, 0.0, 0.0, 1e-4, 0.0, 0.0)
        val decoded = BeiDouD1Decoded(e, 0, 1, 0.0, 0.0, time, 0, 0)
        val capture = GnssCapture(deviceModel = "receiver", osVersion = "os", startedAtMillis = 0,
            endedAtMillis = 60_000, bootCount = 4,
            frames = (0..60).map { index -> GnssObservationFrame(index * 1000L,
                satellites = listOf(GnssSatellite(Constellation.BEIDOU, 1, 30f, 90f, 35f, true, true, true))) },
            beiDouBroadcastEvidence = BeiDouBroadcastEvidence(
                BeiDouElapsedClockEvidence(4, time.week, time.secondsOfWeek, 5_000_000_000L),
                BeiDouReceiverFrame.BDCS, BeiDouServiceValidity(time, validUntil), d1 = listOf(decoded)))
        val result = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
        assertEquals(capture.beiDouBroadcastEvidence, result.beiDouBroadcastEvidence)
        assertEquals(4, ReceiverBroadcastSkyModel(result).broadcastBootCount)
    }
}
