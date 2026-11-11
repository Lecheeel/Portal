package com.system.location.service.core.gnss

/**
 * Optional transfer of a captured signal environment, never of its satellite geometry or usage.
 * Conditional CN0 means include the source receiver, obstructions and propagation. They cannot
 * identify a hardware gain or prove the target site's reception. In the hypothetical receiver
 * they replace the nominal elevation-dependent signal baseline; target range, selected scenario
 * attenuation and deterministic fading are then applied independently. No extrapolation or
 * forced monotonic fit is performed. A signal outside the captured band/elevation uses the model.
 */
class GnssAutonomousSignalCalibration(private val capture: GnssCapture) {
    private val gps = GpsEmpiricalSignalModel(capture)
    private val broadcast = capture.broadcastSignalCalibration ?: BroadcastSignalCalibration.fit(capture)

    fun isCurrent(validationAtMillis: Long): Boolean = validationAtMillis >= capture.endedAtMillis &&
        validationAtMillis - capture.endedAtMillis <= GnssCaptureValidator.MAX_AGE_MILLIS

    fun cn0At(orbit: GnssOrbitPosition, elevation: Double): Double? {
        val carrier = orbit.carrierHz ?: when (orbit.constellation) {
            Constellation.GPS, Constellation.GALILEO -> 1_575_420_000f
            Constellation.BEIDOU -> 1_561_098_000f
            else -> return null
        }
        return when (orbit.constellation) {
            Constellation.GPS -> gps.at(elevation)?.takeIf {
                it.carrierHz != null && kotlin.math.abs(it.carrierHz - carrier) <= 100_000f
            }?.cn0DbHz
            Constellation.GALILEO, Constellation.BEIDOU -> broadcast.curves.firstOrNull { curve ->
                curve.constellation == orbit.constellation && curve.nominalCarrierHz?.let {
                    kotlin.math.abs(it - carrier.toDouble()) <= 100_000.0
                } == true
            }?.at(elevation)?.cn0
            // A capture's slot-to-FCN assignment cannot establish the target epoch's channel.
            Constellation.GLONASS -> broadcast.at(orbit.constellation, elevation, svid = orbit.svid)?.takeIf {
                it.carrier != null && kotlin.math.abs(it.carrier - carrier) <= 100_000f
            }?.cn0
            else -> null
        }
    }
}
