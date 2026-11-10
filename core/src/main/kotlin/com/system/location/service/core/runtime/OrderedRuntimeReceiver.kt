package com.system.location.service.core.runtime

import com.system.location.service.core.gnss.CapturedGnssSource

/** Decode and validate a complete message before changing any live receiver state. */
class OrderedRuntimeReceiver(
    private val currentSource: () -> CapturedGnssSource?,
    private val restore: (RuntimeTransfer, Long) -> Unit,
) {
    private val gate = SessionSequenceGate()

    fun begin(session: String) = gate.reset(session)

    fun receive(session: String, sequence: Long, encoded: ByteArray, nowNanos: Long): Boolean =
        gate.apply(session, sequence) {
            val prepared = RuntimeTransferWire.decode(encoded, currentSource()).atReception(nowNanos)
            restore(prepared, nowNanos)
            true
        }
}
