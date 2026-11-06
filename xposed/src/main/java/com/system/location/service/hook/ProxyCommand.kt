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

/** P5-08: proxy registration/forwarding extracted from RemoteCommandHandler. */
object ProxyCommand {
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
