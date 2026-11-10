package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class BroadcastSignalCalibrationTest {
    private fun signal(system: Constellation, elevation: Float, cn0: Float, carrier: Float?, id: Int = 1,
        used: Boolean = true) = GnssSatellite(system, id, elevation, 90f, cn0, used, true, used, carrier)

    private fun capture(samples: List<GnssSatellite>) = GnssCapture(deviceModel = "synthetic RF fixture",
        osVersion = "test", startedAtMillis = 0, endedAtMillis = 60_000,
        frames = listOf(GnssObservationFrame(0L, satellites = GnssSignalProjection.spacecraft(samples.map(::GnssSignalObservation)),
            signalObservations = samples.map(::GnssSignalObservation))))

    @Test fun jointMeansInterpolateOnlyInsideObservedElevationSupport() {
        val calibration = BroadcastSignalCalibration.fit(capture(listOf(
            signal(Constellation.GALILEO, 10f, 20f, 1_575_420_000f, used = false),
            signal(Constellation.GALILEO, 30f, 40f, 1_575_420_000f))))
        assertNull(calibration.at(Constellation.GALILEO, 9.999, 1_575_420_000.0))
        assertNull(calibration.at(Constellation.GALILEO, 30.001, 1_575_420_000.0))
        val midpoint = calibration.at(Constellation.GALILEO, 20.0, 1_575_420_000.0)!!
        assertEquals(30.0, midpoint.cn0, 1e-12)
        assertEquals(0.5, midpoint.used, 1e-12)
        assertEquals(20.0, calibration.at(Constellation.GALILEO, 10.0, 1_575_420_000.0)!!.cn0, 0.0)
    }

    @Test fun multibandAndConstellationFitsRemainDistinctAndOrderIndependent() {
        val samples = listOf(signal(Constellation.GALILEO, 20f, 21f, 1_575_420_000f),
            signal(Constellation.GALILEO, 20f, 43f, 1_207_140_000f),
            signal(Constellation.GALILEO, 20f, 32f, 1_176_450_000f),
            signal(Constellation.BEIDOU, 20f, 35f, 1_561_098_000f),
            signal(Constellation.BEIDOU, 20f, 60f, 1_575_420_000f),
            signal(Constellation.GALILEO, 20f, 60f, null),
            signal(Constellation.GALILEO, 20f, 0f, 1_575_420_000f),
            signal(Constellation.GALILEO, -1f, 63f, 1_575_420_000f))
        val fitted = BroadcastSignalCalibration.fit(capture(samples))
        assertEquals(fitted, BroadcastSignalCalibration.fit(capture(samples.reversed())))
        assertEquals(4, fitted.curves.size)
        assertEquals(21.0, fitted.at(Constellation.GALILEO, 20.0, 1_575_420_000.0)!!.cn0, 0.0)
        assertEquals(43.0, fitted.at(Constellation.GALILEO, 20.0, 1_207_140_000.0)!!.cn0, 0.0)
        assertEquals(35.0, fitted.at(Constellation.BEIDOU, 20.0, 1_561_098_000.0)!!.cn0, 0.0)
    }

    @Test fun glonassRetainsSlotCarrierEvidenceAndDoesNotMixL2OrBorrowAnotherSlot() {
        val fitted = BroadcastSignalCalibration.fit(capture(listOf(
            signal(Constellation.GLONASS, 20f, 25f, 1_602_000_000f),
            signal(Constellation.GLONASS, 20f, 63f, 1_246_000_000f),
            signal(Constellation.GLONASS, 20f, 45f, 1_602_562_500f, id = 2))))
        assertEquals(25.0, fitted.at(Constellation.GLONASS, 20.0, svid = 1)!!.cn0, 0.0)
        assertEquals(1_602_000_000f, fitted.at(Constellation.GLONASS, 20.0, svid = 1)!!.carrier)
        assertEquals(45.0, fitted.at(Constellation.GLONASS, 20.0, svid = 2)!!.cn0, 0.0)
        assertNull(fitted.at(Constellation.GLONASS, 20.0, svid = 3))
    }
}
