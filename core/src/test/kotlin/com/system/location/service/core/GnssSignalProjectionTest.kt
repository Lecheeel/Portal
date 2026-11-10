package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssSignalProjectionTest {
    private val l1 = GnssSatellite(Constellation.GPS, 1, 35f, 120f, 40f, false, true, true, 1575.42e6f)
    private val l5 = l1.copy(carrierHz = 1176.45e6f, cn0DbHz = 32f, usedInFix = true)
    private val signals = listOf(GnssSignalObservation(l1, 38f), GnssSignalObservation(l5, 30f))

    @Test fun projectionIsOrderIndependentAndKeepsUsedSignalFieldsTogether() {
        assertEquals(listOf(l5), GnssSignalProjection.spacecraft(signals))
        assertEquals(GnssSignalProjection.spacecraft(signals), GnssSignalProjection.spacecraft(signals.reversed()))
        val bothUsed = signals.map { it.copy(satellite = it.satellite.copy(usedInFix = true)) }
        assertEquals(listOf(l5), GnssSignalProjection.spacecraft(bothUsed))
    }

    @Test fun localStoreRetainsBothBandsButProfileCountsOneSpacecraft() {
        val capture = GnssCapture(deviceModel = "device", osVersion = "os", startedAtMillis = 1, endedAtMillis = 2,
            frames = listOf(GnssObservationFrame(1, satellites = GnssSignalProjection.spacecraft(signals),
                signalObservations = signals)))
        val decoded = GnssCaptureCodec.decode(GnssCaptureCodec.encode(capture))
        assertEquals(signals, decoded.frames.single().signalObservations)
        assertEquals(1, decoded.profile().totalSatelliteSamples)
        val wire = GnssCaptureWire.decode(GnssCaptureWire.encode(capture))
        assertTrue(wire.frames.single().signalObservations.isEmpty())
        assertEquals(listOf(l5), wire.frames.single().satellites)
    }

    @Test fun invalidCarrierAndBasebandValuesAreRejected() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertThrows(IllegalArgumentException::class.java) { GnssSignalObservation(l1, bad) }
            assertThrows(IllegalArgumentException::class.java) { GnssSignalObservation(l1.copy(carrierHz = bad)) }
        }
    }

    @Test fun unusableBandDoesNotHideUsableSignalFromTheSameSpacecraft() {
        for (unusable in listOf(l5.copy(cn0DbHz = 0f), l5.copy(elevationDeg = -1f),
            l5.copy(azimuthDeg = 360f))) {
            val records = listOf(GnssSignalObservation(unusable), GnssSignalObservation(l1))
            assertEquals(listOf(l1), GnssSignalProjection.spacecraft(records))
            assertEquals(listOf(l1), GnssSignalProjection.spacecraft(records.reversed()))
        }
        // If no signal is usable, retain one row for diagnosis; quality checks reject it.
        val zero = l1.copy(cn0DbHz = 0f)
        assertEquals(listOf(zero), GnssSignalProjection.spacecraft(listOf(GnssSignalObservation(zero))))
    }
}
