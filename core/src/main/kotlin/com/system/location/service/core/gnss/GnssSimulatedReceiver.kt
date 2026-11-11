package com.system.location.service.core.gnss

import kotlin.math.*

data class GnssReceiverSignal(
    val satellite: GnssSatellite,
    val tracked: Boolean,
    val simulatedNavigation: Boolean,
)

/**
 * A steady-state hypothetical receiver, not a claim about measured CN0 or navigation bits.
 * Slant-range loss, bounded atmospheric loss, environment attenuation and slow deterministic
 * fading are separate from orbit geometry. A captured cold-start window is never looped here.
 */
class GnssSimulatedReceiver(private val seed: Long) {
    fun observe(orbit: GnssOrbitPosition, request: GnssSkyRequest,
        environment: GnssSimulationEnvironment, navigation: GnssSimulatedNavigation? = null,
        calibration: GnssAutonomousSignalCalibration? = null): GnssReceiverSignal? {
        require(navigation == null || navigation.matches(orbit, request)) {
            "Synthetic trajectory/clock does not match this orbit source, identity or epoch"
        }
        val line = GnssObserverGeometry.lineOfSight(request.observer, request.altitudeMeters, orbit.position)
            ?: return null
        if (!line.aboveHorizon) return null
        // At exact zenith the azimuth is undefined; use an explicit presentation convention.
        val azimuth = line.azimuthDegrees ?: 0.0
        val elevation = line.elevationDegrees
        val identity = seed xor (orbit.constellation.ordinal.toLong() shl 32) xor orbit.svid.toLong()
        val phase = java.util.Random(identity).nextDouble() * 2 * PI
        val environmentPhase = java.util.Random(seed).nextDouble() * 2 * PI
        val clockPhase = (request.atMillis % 240_000) / 240_000.0 * 2 * PI
        val fading = 1.5 * sin(clockPhase + phase) + 0.5 * sin(clockPhase * 2 + phase * 3)
        val sine = sin(Math.toRadians(elevation))
        val atmosphericLoss = 1.5 * (1 / sqrt(sine * sine + 0.01) - 1 / sqrt(1.01))
        val environmentLoss = when (environment) {
            GnssSimulationEnvironment.OPEN_SKY -> 0.0
            GnssSimulationEnvironment.INDOOR -> 30.0
            GnssSimulationEnvironment.URBAN -> 6.0 + 12.0 *
                (0.5 + 0.5 * sin(Math.toRadians(azimuth) * 2 + environmentPhase)) * exp(-elevation / 25)
        }
        val baseline = calibration?.cn0At(orbit, elevation) ?: (45.0 - atmosphericLoss)
        val cn0 = (baseline - 20 * log10(line.rangeMeters / 20_200_000.0) -
            environmentLoss + fading).coerceIn(0.0, GnssFrame.MAX_CN0_DB_HZ.toDouble()).toFloat()
        val tracked = cn0 >= 18f
        val hasNavigation = orbit.hasEphemeris || navigation != null
        // These are candidate usage flags. The final combined observation matrix must still
        // have sufficient independent observations and rank before output can claim a fix.
        val usedCandidate = tracked && cn0 >= 25f && elevation >= 5.0 && hasNavigation
        val displayAzimuth = azimuth.toFloat().let { if (it >= 360f) 0f else it }
        val satellite = GnssSatellite(orbit.constellation, orbit.svid, elevation.toFloat(), displayAzimuth,
            cn0, usedCandidate, hasNavigation, orbit.hasAlmanac, orbit.carrierHz ?: carrier(orbit.constellation))
        return GnssReceiverSignal(satellite, tracked, navigation != null)
    }

    private fun carrier(constellation: Constellation): Float? = when (constellation) {
        Constellation.GPS, Constellation.GALILEO, Constellation.QZSS, Constellation.SBAS -> 1_575_420_000f
        Constellation.BEIDOU -> 1_561_098_000f
        Constellation.IRNSS -> 1_176_450_000f
        // A GLONASS slot number does not establish its FDMA channel.
        Constellation.GLONASS -> null
    }
}
