package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.system.location.service.core.geo.Wgs84
import org.junit.Test

class BeiDouBroadcastEvidenceTest {
    private fun datum() = GnssReceiverDatumTransform("synthetic WGS84/BDCS fixture", 4, 0L, 10_000_000_000L,
        listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), listOf(0.0, 0.0, 0.0),
        1.0, 6378137.0, 298.257223563)

    @Test fun productionBeiDouSkyUsesExplicitApplicableReceiverDatum() {
        val time = BeiDouTime(700, 10_000.0)
        val observer = Wgs84(0.0, 0.0)
        val e = BeiDouBroadcastEphemeris(1, BeiDouOrbitType.MEO, time, time, time.plusSeconds(-600.0), time.plusSeconds(600.0),
            5_440.0, 0.01, 0.0, 0.0, 0.0, 0.1, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val decoded = BeiDouD1Decoded(e, 0, 1, 0.0, 0.0, time, 0, 0)
        val service = BeiDouServiceValidity(time.plusSeconds(-600.0), time.plusSeconds(600.0))
        fun output(transform: GnssReceiverDatumTransform?, elapsed: Long = 0L): ReceiverGnssFrame {
            val capture = GnssCapture(deviceModel = "synthetic datum fixture", osVersion = "test", startedAtMillis = 0,
                endedAtMillis = 60_000, bootCount = 4,
                frames = (0..60).map { second -> GnssObservationFrame(second * 1000L, observer,
                    satellites = listOf(GnssSatellite(Constellation.BEIDOU, 1, second * 1.5f, 0f, 35f,
                        true, true, true, 1_561_098_000f))) },
                beiDouBroadcastEvidence = BeiDouBroadcastEvidence(BeiDouElapsedClockEvidence(4, time.week,
                    time.secondsOfWeek, 0L), BeiDouReceiverFrame.BDCS, service, listOf(decoded), receiverTransform = transform))
            val wire = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
            assertEquals(transform, wire.beiDouBroadcastEvidence!!.receiverTransform)
            return ReceiverBroadcastSkyModel(wire).frame(60_000, observer, 0.0, 128, elapsed, 4)!!
        }
        val identity = output(datum())
        assertEquals(identity.diagnostics.joinToString(), 1, identity.satellites.size)
        val translated = datum().copy(translationMeters = listOf(100_000.0, -200_000.0, 300_000.0))
        val receiver = translated.receiver(observer, 0.0, 0L, 4)!!
        val expected = BeiDouSignalReception.observe(decoded, time, BdcsReceiver(receiver.position, receiver.orientation),
            BeiDouLegacySignal.B1I, service).observation!!.geometry.lineOfSight
        val actual = output(translated).satellites.single()
        assertEquals(expected.elevationDegrees, actual.elevationDeg.toDouble(), 1e-5)
        assertEquals(expected.azimuthDegrees!!, actual.azimuthDeg.toDouble(), 1e-5)
        assertTrue(actual.elevationDeg != identity.satellites.single().elevationDeg)
        assertTrue(output(null).satellites.isEmpty())
        assertTrue(output(datum(), 10_000_000_000L).satellites.isEmpty())
    }

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
