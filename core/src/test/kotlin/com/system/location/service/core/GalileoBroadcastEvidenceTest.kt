package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import com.system.location.service.core.geo.Wgs84
import org.junit.Test

class GalileoBroadcastEvidenceTest {
    private fun datum() = GnssReceiverDatumTransform("synthetic WGS84/GTRF fixture", 7, 0L, 10_000_000_000L,
        listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), listOf(0.0, 0.0, 0.0),
        1.0, 6378137.0, 298.257223563)

    @Test fun productionSkyRequiresUsableStatusAndCalibrationForTheSameGalileoSignal() {
        val time = GalileoTime(100, 10_000.0)
        val observer = Wgs84(0.0, 0.0)
        val eph = GalileoBroadcastEphemeris(1, time, time, time.plusSeconds(-600.0), time.plusSeconds(600.0), 1,
            GalileoClockReference.INAV_E1_E5B, 5_440.0, 0.01, 0.0, 0.0, 0.0, 0.1,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val good = GalileoSignalStatus(0, 0)
        val decoded = DecodedGalileoInav(eph, time, good, good, 10, 0.0, 0.0)
        val fnav = DecodedGalileoFnav(eph.copy(clockReference = GalileoClockReference.FNAV_E1_E5A), time, good, 10, 0.0)
        val clock = GpsElapsedClockEvidence(7, 1124, 10_000.0, 0L, 1e-6, 1e-6)
        val offset = GalileoGpsTimeOffset(100, 2, 0, 0, time.plusSeconds(-600.0), time.plusSeconds(600.0))
        fun output(inav: DecodedGalileoInav? = decoded, fn: DecodedGalileoFnav? = null,
            carrier: Float? = 1_575_420_000f, transform: GnssReceiverDatumTransform? = datum(),
            elapsed: Long = 0L, services: List<GalileoServiceValidity>? = null): ReceiverGnssFrame {
            val applicability = services ?: listOfNotNull(
                inav?.let { GalileoServiceValidity(1, GalileoClockReference.INAV_E1_E5B, it.transmissionTimeOfWordFive,
                    time.plusSeconds(-600.0), time.plusSeconds(600.0)) },
                fn?.let { GalileoServiceValidity(1, GalileoClockReference.FNAV_E1_E5A, it.transmissionTimeOfPageOne,
                    time.plusSeconds(-600.0), time.plusSeconds(600.0)) })
            val capture = GnssCapture(deviceModel = "synthetic health fixture", osVersion = "test", startedAtMillis = 0,
                endedAtMillis = 60_000, bootCount = 7,
                frames = (0..60).map { second -> GnssObservationFrame(second * 1000L, observer,
                    satellites = listOf(GnssSatellite(Constellation.GALILEO, 1, second * 1.5f, 0f, 35f,
                        true, true, true, carrier))) },
                galileoBroadcastEvidence = GalileoBroadcastEvidence(clock, offset, GalileoReceiverFrame.GTRF,
                    listOfNotNull(inav), listOfNotNull(fn), transform, applicability))
            val transported = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
            assertEquals(applicability, transported.galileoBroadcastEvidence!!.serviceValidity)
            return ReceiverBroadcastSkyModel(transported).frame(60_000, observer, 0.0, 128, elapsed, 7)!!
        }
        val healthy = output()
        assertEquals(healthy.diagnostics.joinToString(), 1, healthy.satellites.size)
        for (health in 0..3) for (dvs in 0..1) {
            val status = GalileoSignalStatus(health, dvs)
            assertEquals(status.usable, output(decoded.copy(e1Status = status)).satellites.isNotEmpty())
        }
        assertTrue(output(decoded.copy(sisaIndex = 255)).satellites.isEmpty())
        assertTrue(output(carrier = null).satellites.isEmpty())
        assertTrue(output(carrier = 1_176_450_000f).satellites.isEmpty())
        assertEquals(1, output(carrier = 1_207_140_000f).satellites.size)
        assertTrue(output(decoded.copy(e5bStatus = GalileoSignalStatus(1, 0)), carrier = 1_207_140_000f).satellites.isEmpty())
        assertEquals(1, output(null, fnav, 1_176_450_000f).satellites.size)
        assertTrue(output(null, fnav.copy(e5aStatus = GalileoSignalStatus(0, 1)), 1_176_450_000f).satellites.isEmpty())
        assertTrue(output(null, fnav, 1_575_420_000f).satellites.isEmpty())
        assertTrue(output(transform = null).satellites.isEmpty())
        assertTrue(output(elapsed = 10_000_000_000L).satellites.isEmpty())
        val transformed = datum().copy(translationMeters = listOf(100_000.0, -200_000.0, 300_000.0))
        val receiver = transformed.receiver(observer, 0.0, 0L, 7)!!
        val expected = GalileoReceptionGeometry.observe(eph, time,
            GtrfReceiver(receiver.position, receiver.orientation)).observation!!.lineOfSight
        val actual = output(transform = transformed).satellites.single()
        assertEquals(expected.elevationDegrees, actual.elevationDeg.toDouble(), 1e-5)
        assertEquals(expected.azimuthDegrees!!, actual.azimuthDeg.toDouble(), 1e-5)
        assertTrue(actual.elevationDeg != healthy.satellites.single().elevationDeg)
        assertTrue(output(services = emptyList()).satellites.isEmpty())
        assertTrue(output(null, fnav, 1_176_450_000f, services = emptyList()).satellites.isEmpty())
        // Reception is inside [time,time+1), but light-time places emission before time.
        val receptionOnly = GalileoServiceValidity(1, GalileoClockReference.INAV_E1_E5B, time, time, time.plusSeconds(1.0))
        val rejected = output(services = listOf(receptionOnly))
        assertTrue(rejected.satellites.isEmpty())
        assertTrue(rejected.diagnostics.any { it.contains("发射时刻超出") })
        assertThrows(IllegalArgumentException::class.java) {
            output(services = listOf(receptionOnly.copy(broadcastAt = time.plusSeconds(0.1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            output(services = listOf(receptionOnly.copy(format = GalileoClockReference.FNAV_E1_E5A)))
        }
        assertThrows(IllegalArgumentException::class.java) { output(services = listOf(receptionOnly, receptionOnly)) }
    }

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
                inav = listOf(decoded), fnav = listOf(fnav), receiverTransform = datum()))
        val result = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
        assertEquals(capture.galileoBroadcastEvidence, result.galileoBroadcastEvidence)
        assertEquals(7, ReceiverBroadcastSkyModel(result).broadcastBootCount)
    }
}
