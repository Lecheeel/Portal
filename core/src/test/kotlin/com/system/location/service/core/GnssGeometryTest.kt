package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class GnssGeometryTest {
    private fun satellite(id: Int, azimuth: Float, elevation: Float, system: Constellation = Constellation.GPS) =
        GnssSatellite(system, id, elevation, azimuth, 35f, true, true, true)
    private fun geometry() = listOf(satellite(1, 0f, 0f), satellite(2, 90f, 0f),
        satellite(3, 180f, 0f), satellite(4, 270f, 0f), satellite(5, 0f, 90f))

    @Test fun agreesWithAnalyticCardinalAndZenithCovariance() {
        // Direct inversion of H^T H gives Qee=Qnn=1/2, Quu=5/4, Qtt=1/4.
        val dop = GnssGeometry.solve(geometry()).dop!!
        assertEquals(1.0, dop.hdop, 1e-10)
        assertEquals(sqrt(1.25), dop.vdop, 1e-10)
        assertEquals(1.5, dop.pdop, 1e-10)
        assertEquals(0.5, dop.tdop, 1e-10)
        assertEquals(sqrt(2.5), dop.gdop, 1e-10)
        assertEquals(dop.pdop * dop.pdop, dop.hdop * dop.hdop + dop.vdop * dop.vdop, 1e-10)
    }

    @Test fun rejectsInsufficientRankDeficientAndIllConditionedGeometry() {
        assertNull(GnssGeometry.solve(geometry().take(3)).dop)
        assertNull(GnssGeometry.solve((1..8).map { satellite(it, it * 40f, 30f) }).dop)
        assertNull(GnssGeometry.solve((1..8).map { satellite(it, it * 0.00001f, it * 0.00001f) }).dop)
    }

    @Test fun independentSystemClocksRequireAdditionalObservations() {
        val mixed = geometry().mapIndexed { i, s -> s.copy(constellation = Constellation.values()[i]) }
        assertNull(GnssGeometry.solve(mixed).dop)
    }

    @Test fun rotatingAzimuthPreservesDopAndUnusedSatellitesAddNoInformation() {
        val expected = GnssGeometry.solve(geometry()).dop!!
        val rotated = geometry().map { it.copy(azimuthDeg = (it.azimuthDeg + 41f) % 360f) }
        assertEquals(expected.hdop, GnssGeometry.solve(rotated).dop!!.hdop, 1e-10)
        val unused = satellite(10, 130f, 40f).copy(usedInFix = false)
        assertEquals(expected, GnssGeometry.solve(geometry() + unused).dop)
    }

    @Test fun zeroCn0UsedRowsDoNotContributeToGeometry() {
        val expected = GnssGeometry.solve(geometry()).dop!!
        val zero = satellite(10, 130f, 40f).copy(cn0DbHz = 0f)
        assertEquals(expected, GnssGeometry.solve(geometry() + zero).dop)
        assertNull(GnssGeometry.solve(geometry().take(3) + zero).dop)
    }
}
