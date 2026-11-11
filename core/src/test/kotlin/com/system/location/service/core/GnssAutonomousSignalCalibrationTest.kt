package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssAutonomousSignalCalibrationTest {
    private val at = 1_800_000_000_000L
    private val request = GnssSkyRequest(at, Wgs84(0.0, 0.0), 0.0)
    private val capture = GnssCapture(deviceModel = "test", osVersion = "test",
        startedAtMillis = at - 1000, endedAtMillis = at,
        frames = listOf(GnssObservationFrame(at, Wgs84(-31.0, -59.0), satellites = listOf(
            GnssSatellite(Constellation.GPS, 1, 20f, 120f, 40f, true, true, true, 1_575_420_000f),
            GnssSatellite(Constellation.GPS, 2, 80f, 300f, 20f, true, true, true, 1_575_420_000f)))))
    private val calibration = GnssAutonomousSignalCalibration(capture)
    private val provenance = GnssOrbitProvenance("almanac", "1", "test", at, GnssOrbitSourceKind.ALMANAC)
    private val orbit = GnssOrbitPosition(Constellation.GPS, 5, EcefPosition(26_560_000.0, 0.0, 0.0), provenance,
        false, true)

    @Test fun interpolationPreservesNegativeSlopeWithoutBorrowingBandOrExtrapolating() {
        assertEquals(30.0, calibration.cn0At(orbit, 50.0)!!, 1e-6)
        assertTrue(calibration.cn0At(orbit, 20.0)!! > calibration.cn0At(orbit, 80.0)!!)
        assertNull(calibration.cn0At(orbit, 10.0))
        assertNull(calibration.cn0At(orbit, 90.0))
        assertNull(calibration.cn0At(orbit.copy(carrierHz = 1_176_450_000f), 50.0))
        assertNull(calibration.cn0At(orbit.copy(constellation = Constellation.GALILEO), 50.0))
    }

    @Test fun expiryUsesValidationClockAndDoesNotChangeGeometryOrInventNavigation() {
        assertTrue(calibration.isCurrent(at + GnssCaptureValidator.MAX_AGE_MILLIS))
        assertFalse(calibration.isCurrent(at - 1))
        assertFalse(calibration.isCurrent(at + GnssCaptureValidator.MAX_AGE_MILLIS + 1))
        val provider = object : GnssOrbitProvider {
            override val kind = GnssOrbitSourceKind.ALMANAC
            override fun positions(request: GnssSkyRequest) = GnssOrbitBatch(listOf(orbit))
        }
        val fallback = object : GnssOrbitProvider {
            override val kind = GnssOrbitSourceKind.VIRTUAL
            override fun positions(request: GnssSkyRequest) = GnssOrbitBatch(emptyList())
        }
        val model = ReceiverAutonomousSkyModel(listOf(provider), fallback, GnssSimulationPolicy(mode = GnssSimulationMode.AUTO),
            signalCalibration = calibration)
        val active = model.frame(request, validationAtMillis = at)!!
        val expired = model.frame(request, validationAtMillis = at + GnssCaptureValidator.MAX_AGE_MILLIS + 1)!!
        assertEquals(active.satellites, expired.satellites) // Zenith is outside captured elevation range.
        assertTrue(active.satellites.none { it.usedInFix || it.hasEphemeris })
        assertTrue(active.diagnostics.any { it.contains("迁移采集信号环境") })
        assertTrue(expired.diagnostics.any { it.contains("已过期") })
    }

    @Test fun calibratedSignalChangesCn0ButDoesNotCopyAnglesIdentitiesOrUsage() {
        val target = orbit.copy(position = EcefPosition(23_000_000.0, 12_000_000.0, 0.0))
        val receiver = GnssSimulatedReceiver(123)
        val plain = receiver.observe(target, request, GnssSimulationEnvironment.OPEN_SKY)!!
        val calibrated = receiver.observe(target, request, GnssSimulationEnvironment.OPEN_SKY,
            calibration = calibration)!!
        assertNotEquals(plain.satellite.cn0DbHz, calibrated.satellite.cn0DbHz)
        assertEquals(plain.satellite.copy(cn0DbHz = calibrated.satellite.cn0DbHz), calibrated.satellite)
        assertEquals(5, calibrated.satellite.svid)
        assertFalse(calibrated.satellite.usedInFix)
        assertFalse(calibrated.satellite.hasEphemeris)
    }
}
