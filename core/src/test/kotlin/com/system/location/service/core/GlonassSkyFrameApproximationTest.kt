package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.assertEquals
import org.junit.Test

class GlonassSkyFrameApproximationTest {
    @Test fun epsgCoordinateFrameSignsAndMilliarcsecondUnitsAgreeWithIndependentProj() {
        // pyproj 3.8 / PROJ helmert: x=-.003 y=-.001 z=0 rx=.000019 ry=-.000042
        // rz=.000002 s=0 convention=coordinate_frame (PROJ angles are arcseconds).
        val fixtures = listOf(
            doubleArrayOf(19100000.0, 13400000.0, 10900000.0, 19099999.999349408, 13399999.99981885, 10899999.994876489),
            doubleArrayOf(25500000.0, 0.0, 0.0, 25499999.997, -0.0012472549773658633, -0.00519235452468313),
            doubleArrayOf(0.0, 25500000.0, 0.0, -0.0027527450226341368, 25499999.999, -0.0023489222849757017),
            doubleArrayOf(0.0, 0.0, 25500000.0, 0.00219235452468313, 0.0013489222849757017, 25500000.0),
        )
        fixtures.forEach { v ->
            val transformed = GlonassSkyFrameApproximation.pz9011ToItrf2008(GlonassCartesianVector(v[0], v[1], v[2]))
            assertEquals(v[3], transformed.xMeters, 1e-8)
            assertEquals(v[4], transformed.yMeters, 1e-8)
            assertEquals(v[5], transformed.zMeters, 1e-8)
        }
    }
}
