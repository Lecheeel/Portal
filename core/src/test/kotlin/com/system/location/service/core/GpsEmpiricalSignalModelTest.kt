package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GpsEmpiricalSignalModelTest {
    private fun satellite(elevation: Float, cn0: Float, used: Boolean) =
        GnssSatellite(Constellation.GPS, 1, elevation, 0f, cn0, used, true, true)
    private fun capture(satellites: List<GnssSatellite>) = GnssCapture(deviceModel = "test", osVersion = "test",
        startedAtMillis = 0, endedAtMillis = satellites.size.toLong(), frames = satellites.mapIndexed { i, s ->
            GnssObservationFrame(i.toLong(), satellites = listOf(s))
        })

    @Test fun interpolatesPairedEmpiricalMeansWithoutInventingAUniversalPowerCurve() {
        val c = capture(listOf(satellite(20f, 10f, false), satellite(20f, 30f, true), satellite(60f, 40f, true)))
        val m = GpsEmpiricalSignalModel(c)
        val at40 = m.at(40.0)!!
        assertEquals(30.0, at40.cn0DbHz, 0.0)
        assertEquals(0.75, at40.usedFraction, 0.0)
        assertNull(at40.carrierHz)
        assertEquals(20.0, m.at(20.0)!!.cn0DbHz, 0.0)
        assertEquals(40.0, m.at(60.0)!!.cn0DbHz, 0.0)
        assertNull(m.at(19.999))
        assertNull(m.at(60.001))
        val differentEnvironment = GpsEmpiricalSignalModel(c.copy(frames = c.frames.map { f ->
            f.copy(satellites = f.satellites.map { it.copy(cn0DbHz = it.cn0DbHz - 5f) })
        }))
        assertEquals(25.0, differentEnvironment.at(40.0)!!.cn0DbHz, 0.0)
    }

    @Test fun otherConstellationsAndKnownNonL1SignalsCannotCalibrateGpsLnavOutput() {
        val sample = satellite(30f, 35f, true)
        val m = GpsEmpiricalSignalModel(capture(listOf(sample.copy(constellation = Constellation.BEIDOU),
            sample.copy(carrierHz = 1_176_450_000f))))
        assertNull(m.at(30.0))
        val single = GpsEmpiricalSignalModel(capture(listOf(sample)))
        assertNotNull(single.at(30.0))
        assertNull(single.at(30.001))
    }

    @Test fun perSignalL1SurvivesL5SpacecraftProjectionAndCompactRoundTrips() {
        val l1 = satellite(30f, 25f, false).copy(carrierHz = 1_575_420_000f)
        val l5 = satellite(30f, 50f, true).copy(carrierHz = 1_176_450_000f)
        val signals = listOf(GnssSignalObservation(l1), GnssSignalObservation(l5))
        val c = capture(listOf(l5)).copy(frames = listOf(GnssObservationFrame(0,
            satellites = GnssSignalProjection.spacecraft(signals), signalObservations = signals)))
        assertEquals(l5, c.frames.single().satellites.single())
        val expected = GpsEmpiricalSignalModel(c).at(30.0)!!
        assertEquals(25.0, expected.cn0DbHz, 0.0)
        assertEquals(0.0, expected.usedFraction, 0.0)
        assertEquals(l1.carrierHz, expected.carrierHz)
        var compact = GnssCaptureWire.decode(GnssCaptureWire.encode(c))
        repeat(2) {
            assertTrue(compact.frames.single().signalObservations.isEmpty())
            assertEquals(expected, GpsEmpiricalSignalModel(compact).at(30.0))
            compact = GnssCaptureWire.decode(GnssCaptureWire.encode(compact))
        }
        // Fresh per-signal input supersedes a previously transported calibration.
        val updated = compact.copy(frames = c.frames.map { frame -> frame.copy(signalObservations =
            listOf(GnssSignalObservation(l1.copy(cn0DbHz = 40f)), GnssSignalObservation(l5))) })
        assertEquals(40.0, GpsEmpiricalSignalModel(updated).at(30.0)!!.cn0DbHz, 0.0)
    }

    @Test fun malformedCalibrationCannotEnterTheInterpolator() {
        val signal = GpsEmpiricalSignal(30.0, 0.5, false, null)
        assertThrows(IllegalArgumentException::class.java) { GpsEmpiricalSignal(Double.NaN, 0.0, false, null) }
        assertThrows(IllegalArgumentException::class.java) { GpsEmpiricalSignal(30.0, 1.1, false, null) }
        assertThrows(IllegalArgumentException::class.java) { GpsEmpiricalSignal(30.0, 0.5, false, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { GpsEmpiricalSignalKnot(-1.0, signal) }
        assertThrows(IllegalArgumentException::class.java) { GpsSignalCalibration(listOf(
            GpsEmpiricalSignalKnot(30.0, signal), GpsEmpiricalSignalKnot(30.0, signal))) }
    }
}
