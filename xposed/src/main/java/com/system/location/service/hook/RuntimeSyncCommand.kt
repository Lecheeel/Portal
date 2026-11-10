package com.system.location.service.hook

import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.system.location.service.core.runtime.OrderedRuntimeReceiver
import com.system.location.service.core.runtime.RuntimeTransferWire
import com.system.location.service.hook.utils.FakeLoc

/** UID-authenticated full-state receiver. No forwarded control commands or native enable side effects. */
object RuntimeSyncCommand {
    const val TRANSACTION = 2
    const val PAYLOAD = "runtime_transfer"
    const val SESSION = "proxy_session"
    const val SEQUENCE = "proxy_sequence"
    private val receiver = OrderedRuntimeReceiver({ FakeLoc.runtimeSnapshots.current().configuration.gnssSource }) { state, _ ->
        val now = SystemClock.elapsedRealtimeNanos()
        val restored = FakeLoc.restoreRuntime(state, now)
        check(SampleCommand.restoreLease(restored.state.sample, now))
        if (restored.externallyDriven) LocationTicker.start() else LocationTicker.stop()
    }

    @Synchronized fun begin(session: String) {
        receiver.begin(session)
        FakeLoc.stopRuntime()
        SampleCommand.clearLease()
        LocationTicker.stop()
    }

    internal data class Incoming(val session: String, val sequence: Long, val encoded: ByteArray)

    internal fun prepare(values: Map<String, Any?>): Incoming {
        val session = values[SESSION] as? String ?: error("Missing proxy session")
        val sequence = values[SEQUENCE] as? Long ?: error("Missing state sequence")
        val encoded = values[PAYLOAD] as? ByteArray ?: error("Missing complete runtime state")
        require(session.isNotBlank() && session.length <= 128 && sequence > 0)
        require(encoded.isNotEmpty() && encoded.size <= RuntimeTransferWire.MAX_COMPRESSED_BYTES)
        return Incoming(session, sequence, encoded)
    }

    @Suppress("DEPRECATION")
    @Synchronized fun receive(rely: Bundle): Boolean {
        if (Binder.getCallingUid() != 1000 || FakeLoc.isSystemServerProcess) return false
        val message = prepare(listOf(SESSION, SEQUENCE, PAYLOAD).associateWith { rely.get(it) })
        return receiver.receive(message.session, message.sequence, message.encoded, SystemClock.elapsedRealtimeNanos())
    }
}
