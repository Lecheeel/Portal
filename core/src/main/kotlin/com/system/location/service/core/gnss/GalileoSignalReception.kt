package com.system.location.service.core.gnss

data class GalileoSignalReceptionObservation(
    val geometry: GalileoReceptionObservation,
    val signal: GalileoOpenServiceSignal,
    /** Single-frequency satellite clock evaluated at geometry.transmissionTime, including BGD. */
    val signalClockBiasSeconds: Double,
)

data class GalileoSignalReceptionResult(val observation: GalileoSignalReceptionObservation?, val reason: String? = null)

/** Joins decoded per-signal usability/clock evidence to geometric vacuum light-time. */
object GalileoSignalReception {
    fun observe(decoded: DecodedGalileoInav, receptionTime: GalileoTime, receiver: GtrfReceiver,
        signal: GalileoOpenServiceSignal): GalileoSignalReceptionResult =
        evaluate(decoded.ephemeris, receptionTime, receiver, signal) { transmission ->
            GalileoSignalClock.correction(decoded, transmission, signal)
        }

    fun observe(decoded: DecodedGalileoFnav, receptionTime: GalileoTime, receiver: GtrfReceiver,
        signal: GalileoOpenServiceSignal): GalileoSignalReceptionResult =
        evaluate(decoded.ephemeris, receptionTime, receiver, signal) { transmission ->
            GalileoSignalClock.correction(decoded, transmission, signal)
        }

    private fun evaluate(e: GalileoBroadcastEphemeris, receptionTime: GalileoTime, receiver: GtrfReceiver,
        signal: GalileoOpenServiceSignal, clock: (GalileoTime) -> GalileoSignalClockResult): GalileoSignalReceptionResult {
        val result = GalileoReceptionGeometry.observe(e, receptionTime, receiver)
        val geometry = result.observation ?: return GalileoSignalReceptionResult(null, result.reason)
        // Reception can follow the validity end; the propagated transmission epoch is authoritative.
        val correction = clock(geometry.transmissionTime)
        val bias = correction.correctionSeconds ?: return GalileoSignalReceptionResult(null, correction.reason)
        return GalileoSignalReceptionResult(GalileoSignalReceptionObservation(geometry, signal, bias))
    }
}
