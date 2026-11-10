package com.system.location.service.core.gnss

data class BeiDouSignalReceptionObservation(
    val geometry: BeiDouReceptionObservation,
    val signal: BeiDouLegacySignal,
    val signalClockBiasSeconds: Double,
)
data class BeiDouSignalReceptionResult(val observation: BeiDouSignalReceptionObservation?, val reason: String? = null)

/** Apply service validity, SatH1/URAI and TGD at the solved BDT transmission epoch. */
object BeiDouSignalReception {
    fun observe(decoded: BeiDouD1Decoded, receptionTime: BeiDouTime, receiver: BdcsReceiver,
        signal: BeiDouLegacySignal, serviceValidity: BeiDouServiceValidity): BeiDouSignalReceptionResult =
        evaluate(decoded.ephemeris, receptionTime, receiver, signal) { transmission ->
            BeiDouSignalClock.correction(decoded, transmission, signal, serviceValidity)
        }

    fun observe(decoded: BeiDouD2Decoded, receptionTime: BeiDouTime, receiver: BdcsReceiver,
        signal: BeiDouLegacySignal, serviceValidity: BeiDouServiceValidity): BeiDouSignalReceptionResult =
        evaluate(decoded.ephemeris, receptionTime, receiver, signal) { transmission ->
            BeiDouSignalClock.correction(decoded, transmission, signal, serviceValidity)
        }

    private fun evaluate(e: BeiDouBroadcastEphemeris, receptionTime: BeiDouTime, receiver: BdcsReceiver,
        signal: BeiDouLegacySignal, clock: (BeiDouTime) -> BeiDouSignalClockResult): BeiDouSignalReceptionResult {
        val result = BeiDouReceptionGeometry.observe(e, receptionTime, receiver)
        val geometry = result.observation ?: return BeiDouSignalReceptionResult(null, result.reason)
        val correction = clock(geometry.transmissionTime)
        val bias = correction.correctionSeconds ?: return BeiDouSignalReceptionResult(null, correction.reason)
        return BeiDouSignalReceptionResult(BeiDouSignalReceptionObservation(geometry, signal, bias))
    }
}
