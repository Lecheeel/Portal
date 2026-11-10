package com.system.location.service.hook

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.system.location.service.hook.security.CommandSecurity
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import java.util.Collections
import com.system.location.service.core.runtime.RuntimeTransferWire

/** P5-08: proxy registration/forwarding extracted from RemoteCommandHandler. */
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

    private val needProxyCmd = arrayOf(
        "start", "stop", "set_speed_amp", "set_altitude", "set_speed", "update_location",
        "set_bearing", "move", "put_config", "publish_sample",
    )
    private val binders = Collections.synchronizedList(arrayListOf<IBinder>())
    private var proxySequence = 0L
    private var lastProxySequence = 0L

    @Synchronized
    fun resetSession() {
        lastProxySequence = 0
    }

    @Synchronized
    fun acceptIncoming(rely: Bundle): Boolean {
        if (Binder.getCallingUid() != 1000 || FakeLoc.isSystemServerProcess) return false
        val sequence = rely.getLong("proxy_sequence")
        val sent = rely.getLong("sent_at", -1)
        val now = SystemClock.elapsedRealtime()
        if (sequence <= lastProxySequence || sent < 0 || sent > now ||
            now - sent > CommandSecurity.MAX_AGE_MS ||
            rely.getString("command_id") !in needProxyCmd) return false
        lastProxySequence = sequence
        return true
    }

    fun add(rely: Bundle): Boolean {
        Logger.info("SubProxyBinder: ${rely.getBinder("proxy")} from ${BinderUtils.getUidPackageNames()}!")
        rely.getBinder("proxy")?.let { binders.add(it) }
        return true
    }

    fun forward(rely: Bundle, commandId: String) {
        kotlin.runCatching {
            if (binders.isNotEmpty() && needProxyCmd.any { it == commandId }) {
                rely.putLong("proxy_sequence", ++proxySequence)
                binders.removeIf {
                    if (it.isBinderAlive && it.pingBinder()) {
                        val data = Parcel.obtain()
                        try {
                            data.writeBundle(rely)
                            // Forward as system_server, not as the app identity inherited by this Binder call.
                            val identity = Binder.clearCallingIdentity()
                            try {
                                it.transact(1, data, null, 0)
                            } finally {
                                Binder.restoreCallingIdentity(identity)
                            }
                        } finally {
                            data.recycle()
                        }
                        false
                    } else {
                        true
                    }
                }
            }
        }.onFailure {
            Logger.error("Failed to transact with proxyBinder", it)
        }
    }
}
