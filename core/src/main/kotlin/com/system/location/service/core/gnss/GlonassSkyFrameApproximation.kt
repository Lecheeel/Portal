package com.system.location.service.core.gnss

/**
 * EPSG:7960 PZ-90.11 -> ITRF2008, EPSG:1066 coordinate-frame convention, reference epoch
 * 2010.0. Rotations are milliarcseconds, translations metres, scale zero. This is the stated
 * linearized EPSG operation, not the position-vector convention with the opposite signs.
 * https://epsg.io/7960
 *
 * A sky adapter may then explicitly approximate ITRF2008 as WGS84-compatible axes. These
 * fixed reference-epoch parameters do NOT establish an exact contemporary WGS84 realization,
 * epoch propagation, centimetre accuracy, or a precise positioning solution.
 */
object GlonassSkyFrameApproximation {
    fun pz9011ToItrf2008(position: GlonassCartesianVector): EcefPosition {
        val masToRadians = Math.PI / (180 * 3600 * 1000)
        val rx = 0.019 * masToRadians
        val ry = -0.042 * masToRadians
        val rz = 0.002 * masToRadians
        return EcefPosition(
            -0.003 + position.x + rz * position.y - ry * position.z,
            -0.001 - rz * position.x + position.y + rx * position.z,
            ry * position.x - rx * position.y + position.z,
        )
    }
}
