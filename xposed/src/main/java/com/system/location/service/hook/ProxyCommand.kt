package com.system.location.service.hook

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.core.runtime.RuntimeTransferWire

/** Ordered full-state replication; no control command is replayed in another process. */
object ProxyCommand {
    private val statePeers = RuntimePeerRegistry(deliver = { binder, session, publication ->
        val message = Bundle().apply {
            putString(RuntimeSyncCommand.SESSION, session)
            putLong(RuntimeSyncCommand.SEQUENCE, publication.sequence)
            putByteArray(RuntimeSyncCommand.PAYLOAD, RuntimeTransferWire.encode(publication.transfer))
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeBundle(message)
            val identity = Binder.clearCallingIdentity()
            try {
                check(binder.transact(RuntimeSyncCommand.TRANSACTION, data, reply, 0)) { "Peer transaction rejected" }
                reply.readException()
                check(reply.readInt() == 1) { "Peer state rejected" }
            } finally { Binder.restoreCallingIdentity(identity) }
        } finally { data.recycle(); reply.recycle() }
    }, onFailure = { Logger.error("Runtime peer disconnected", it) })
    private var stateSequence = 0L

    /** Called within the authoritative command order, before any subsequent state publication. */
    @Synchronized fun subscribe(rely: Bundle): Boolean {
        if (!FakeLoc.isSystemServerProcess) return false
        val binder = rely.getBinder("proxy") ?: return false
        val name = rely.getString("proxy_name") ?: return false
        val session = rely.getString(RuntimeSyncCommand.SESSION) ?: return false
        if (!statePeers.register(Binder.getCallingUid(), name, session, binder)) return false
        publishCurrent()
        return true
    }

    @Synchronized fun publishCurrent() {
        if (!FakeLoc.isSystemServerProcess || statePeers.size() == 0) return
        stateSequence = Math.addExact(stateSequence, 1L)
        statePeers.offer(RuntimePeerRegistry.Publication(stateSequence, FakeLoc.captureTransfer()))
    }

    internal fun subscriptions(): List<RuntimePeerRegistry.Subscription> = statePeers.subscriptions()
    internal fun restore(subscription: RuntimePeerRegistry.Subscription): Boolean = statePeers.restore(subscription)
    /** Detach old-generation death recipients and pending replication after saving peers. */
    internal fun retireSubscriptions() = statePeers.clear()

}
